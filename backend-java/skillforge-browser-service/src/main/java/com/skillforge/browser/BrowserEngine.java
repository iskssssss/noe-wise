package com.skillforge.browser;

import com.fasterxml.jackson.databind.*;
import com.skillforge.execution.browser.*;
import com.skillforge.execution.browser.BrowserReceipt.BrowserBinding;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 单实例浏览器执行所有者；提交前落盘，未知副作用不重放，daemon 与 home 按个人隔离。 */
@Service
public class BrowserEngine {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BrowserEngine.class);
    private final Path root;private final String binary,publicBase;private final ObjectMapper json;
    private final BrowserAuthorizationJournal authorizationJournal;
    private final BrowserConnectionGrantStore connectionGrants;
    private final Map<String,Process> daemons=new ConcurrentHashMap<>();
    private final Map<String,Object> locks=new ConcurrentHashMap<>();
    private final Map<String,Set<Process>> activeCommands=new ConcurrentHashMap<>();
    private final Set<String> activeReceipts=ConcurrentHashMap.newKeySet();
    private final Set<String> runningRecordings=ConcurrentHashMap.newKeySet();
    private static final String TASK_ID_PATTERN="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    record DaemonBinding(int port){}
    record RecordingBinding(String receipt,String stopReceipt,String task,String output){}
    record DeviceLabel(String deviceId,String label){}
    record DevicePreferences(String selectedBrowser,List<DeviceLabel> labels){DevicePreferences{labels=List.copyOf(labels);}}
    public BrowserEngine(@Value("${skillforge.browser.data-root}")String root,@Value("${skillforge.browser.binary}")String binary,
            @Value("${skillforge.browser.public-base-url}")String publicBase,ObjectMapper json)throws IOException{
        this.root=Path.of(root).toAbsolutePath().normalize();this.binary=binary;this.publicBase=publicBase;this.json=json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.authorizationJournal=new BrowserAuthorizationJournal(this.json);
        this.connectionGrants=new BrowserConnectionGrantStore(this.json);
        Files.createDirectories(this.root.resolve("owners"));
    }
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    Path home(String tenant,String user){if(tenant==null||user==null||!tenant.matches("[1-9][0-9]*")||!user.matches("[1-9][0-9]*"))throw new IllegalArgumentException("browser_owner_required");return root.resolve("owners").resolve(hash(tenant+":"+user));}
    private Object lock(Path path){return locks.computeIfAbsent(path.toString(),k->new Object());}
    public synchronized int daemon(Path home)throws Exception{return daemon(home,Instant.now().plusSeconds(30));}
    synchronized int daemon(Path home,Instant deadline)throws Exception{
        if(java.io.File.separatorChar=='/'&&home.resolve("run/daemon.sock").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>=108)throw new IllegalArgumentException("browser_data_root_too_long");
        Files.createDirectories(home);Path config=home.resolve("owner.json");DaemonBinding owner;
        if(Files.exists(config))owner=read(config,DaemonBinding.class);
        else{int port;try(var socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}owner=new DaemonBinding(port);write(config,owner);}
        int port=owner.port();String key=home.getFileName().toString();Process running=daemons.get(key);if(running!=null&&running.isAlive())return port;
        try{run(home,List.of("--json","status"),budget(deadline,10));return port;}catch(Exception unavailable){ }
        if(publicBase.isBlank())throw new IllegalStateException("BROWSER_PUBLIC_BASE_URL_required");
        String endpoint=publicBase.replaceAll("/$","")+"/extension/"+key;
        var builder=process(home,List.of(binary,"daemon","start","--mode","server","--listen","127.0.0.1","--port",Integer.toString(port),"--public-url",endpoint));
        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(home.resolve("daemon.log").toFile()));
        Process child=builder.start();daemons.put(key,child);
        for(int i=0;i<40;i++){if(!child.isAlive())throw new IOException("browser_daemon_exit");try{run(home,List.of("--json","status"),budget(deadline,2));return port;}catch(Exception waiting){budget(deadline,2);Thread.sleep(100);}}
        throw new IOException("browser_daemon_not_ready");
    }
    int proxyPort(String key)throws Exception{if(!key.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("browser_route_invalid");Path path=root.resolve("owners").resolve(key);if(!Files.exists(path.resolve("owner.json")))throw new FileNotFoundException("browser_owner_not_found");return daemon(path);}
    ProcessBuilder process(Path home,List<String> command){var p=new ProcessBuilder(command).directory(home.toFile());p.environment().put("BSK_HOME",home.toString());p.environment().put("BSK_AUTO_START","0");p.environment().put("BSK_AUTO_UPDATE","off");p.environment().put("BSK_CANCEL_ON_STDIN_CLOSE","1");if(command.contains("status"))p.environment().put("BSK_BROWSER_WAIT_MS","0");return p;}
    JsonNode run(Path home,List<String> args,int seconds)throws Exception{
        return runOwned(home,args,seconds,null);
    }
    /** wait-ms 无上游 session 参数；使用已经校验的任务绑定归属子进程，供精确 EOF 取消。 */
    JsonNode runInSession(Path home,List<String> args,int seconds,String session)throws Exception{
        return runOwned(home,args,seconds,Objects.requireNonNull(session));
    }
    /** 执行归属由宿主显式提供或按原 CLI 契约推导，不把归属身份追加到上游 argv。 */
    private JsonNode runOwned(Path home,List<String> args,int seconds,String session)throws Exception{
        var command=new ArrayList<String>();command.add(binary);command.addAll(args);Process child=process(home,command).start();
        var stdout=CompletableFuture.supplyAsync(()->readOutput(child.getInputStream()));var stderr=CompletableFuture.supplyAsync(()->readOutput(child.getErrorStream()));
        String commandKey=commandKey(home,args,session);var owned=activeCommands.computeIfAbsent(commandKey,k->ConcurrentHashMap.newKeySet());owned.add(child);
        try{
            if(!child.waitFor(seconds,TimeUnit.SECONDS)){child.destroyForcibly();throw new IOException("browser_cli_result_unknown");}
            String output=stdout.get(5,TimeUnit.SECONDS);stderr.get(5,TimeUnit.SECONDS);
            if(child.exitValue()!=0){
                JsonNode recording=recordingCleanupPending(output,child.exitValue(),args);
                if(recording!=null)throw new RecordingCleanupPending(recording);
                JsonNode error;try{error=json.readTree(output);}catch(Exception invalid){throw new IOException("browser_cli_result_unknown");}
                String code=error.path("code").asText();
                if(confirmedBorrowTimeout(error,child.exitValue(),args))throw new CliFailure("confirmation_timeout");
                if(Set.of("invalid_params","not_found","permission_denied","no_browser_connected","multiple_browsers_online","unsupported","unknown_method","version_too_old","cancelled","user_aborted","cdp_failed","timeout","protocol_error").contains(code))throw new CliFailure(BrowserFailure.fromCli(code,error));
                throw new IOException("browser_cli_result_unknown");
            }
            if(args.contains("pair"))return json.createObjectNode().put("pairing_url",output.trim());
            if(args.contains("revoke"))return json.createObjectNode().put("success",true);
            return json.readTree(output);
        }catch(InterruptedException interrupted){
            // 中断不代表 daemon 已停止命令；保留归属直到精确 EOF 取消及输出读取收尾。
            try{cancelOwnedProcesses(List.of(child));}
            catch(IOException|InterruptedException cleanupFailure){interrupted.addSuppressed(cleanupFailure);}
            // 清理阶段再次中断记录在原异常中，仍向调用者抛出原始 InterruptedException。
            Thread.interrupted();
            try{
                for(var output:List.of(stdout,stderr))try{output.get(5,TimeUnit.SECONDS);}
                catch(ExecutionException|TimeoutException|InterruptedException readFailure){interrupted.addSuppressed(readFailure);Thread.interrupted();}
            }finally{
                for(var stream:List.of(child.getInputStream(),child.getErrorStream()))try{stream.close();}
                catch(IOException closeFailure){interrupted.addSuppressed(closeFailure);}
            }
            throw interrupted;
        }finally{owned.remove(child);}
    }
    /** 固定上游明确拒绝借用的确认超时；普通传输/执行 timeout 仍无法证明副作用。 */
    static boolean confirmedBorrowTimeout(JsonNode error,int exit,List<String> args){
        return exit==4&&args.size()>=3&&args.get(0).equals("--json")&&args.get(1).equals("tab")&&args.get(2).equals("borrow")
                &&error.path("code").asText().equals("timeout")
                &&error.path("data").path("reason").asText().equals("confirmation_timeout");
    }
    /** 仅识别已实测的录制收尾竞争：成功 trace 后紧跟唯一的 stopping 错误。 */
    JsonNode recordingCleanupPending(String output,int exit,List<String> args)throws IOException{
        if(exit!=4||args.size()<3||!args.get(1).equals("record")||!Set.of("start","stop").contains(args.get(2)))return null;
        int outputFlag=args.indexOf("--output");if(outputFlag<0||outputFlag+1>=args.size())return null;
        try(var parser=json.createParser(output)){
            JsonNode trace=json.readTree(parser),failure=json.readTree(parser),extra=json.readTree(parser);
            if(trace==null||failure==null||extra!=null||!trace.isObject()||!trace.path("trace").isObject()
                    ||trace.path("trace_version").asInt()!=3||!trace.path("window_closed").asBoolean()
                    ||!failure.path("code").asText().equals("timeout")||failure.path("exit_code").asInt()!=4
                    ||!failure.path("message").asText().equals("session stop is already in progress"))return null;
            Path directory=Path.of(args.get(outputFlag+1));
            if(!trace.path("output").asText().equals(directory.toString())
                    ||!trace.path("trace_json").asText().equals(directory.resolve("trace.json").toString()))return null;
            return trace;
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){return null;}
    }
    static class RecordingCleanupPending extends IOException{
        final JsonNode trace;
        RecordingCleanupPending(JsonNode trace){super("browser_recording_cleanup_pending");this.trace=trace;}
    }
    /** 无 session flag 的 wait-ms 使用可信冻结会话，其余操作保持原参数归属推导。 */
    private String commandKey(Path home,List<String> args,String ownedSession){if(ownedSession!=null)return home+":"+ownedSession;int session=args.indexOf("--session"),request=args.indexOf("--request-id");String key=session>=0?args.get(session+1):request>=0?args.get(request+1):args.size()>3&&args.get(1).equals("session")&&args.get(2).equals("request")?args.get(3):args.contains("record")&&args.contains("start")?"recording":"control";return home+":"+key;}
    private void interruptOwned(Path home,BrowserReceipt original)throws IOException,InterruptedException{
        String key=home+":"+("record-start".equals(original.operation())?"recording":original.binding()==null?original.invocation():original.binding().sessionId());
        var owned=new HashSet<>(activeCommands.getOrDefault(key,Set.of()));
        if(original.requestToken()!=null)owned.addAll(activeCommands.getOrDefault(home+":"+original.requestToken(),Set.of()));
        cancelOwnedProcesses(owned);
    }
    static void cancelOwnedProcesses(Collection<Process> owned)throws IOException,InterruptedException{
        // 固定上游通过父进程 stdin EOF 发送精确 cancel RPC；SIGKILL 会留下 daemon 中仍运行的命令。
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);IOException failure=null;
        for(Process child:owned)if(child.isAlive())try{child.getOutputStream().close();}catch(IOException closed){failure=new IOException("browser_cancel_pipe_failed",closed);}
        try{
            // 共用上限覆盖官方 cancel frame 与 response 各 2 秒，不按进程数量累加等待。
            for(Process child:owned)if(child.isAlive()){
                long remaining=deadline-System.nanoTime();
                if(remaining<=0||!child.waitFor(remaining,TimeUnit.NANOSECONDS)){
                    child.destroyForcibly();failure=new IOException("browser_cancel_result_unknown");
                }
            }
        }catch(InterruptedException interrupted){owned.stream().filter(Process::isAlive).forEach(Process::destroyForcibly);Thread.currentThread().interrupt();throw interrupted;}
        if(failure!=null)throw failure;
    }
    /** 确认的 CLI 拒绝携带安全原因及效果状态；异常消息本身只保存一级错误码。 */
    static class CliFailure extends IOException{
        final String code;
        final BrowserFailure failure;
        /** 宿主本地已确认错误没有上游细节，不补造 reason 或副作用状态。 */
        CliFailure(String code){this(new BrowserFailure(code,null,null,null));}
        /** 保留已经过固定协议投影的原因，不复制原始 stderr 或页面材料。 */
        CliFailure(BrowserFailure failure){super(failure.error());this.code=failure.error();this.failure=failure;}
    }
    private int budget(Instant deadline,int maximum)throws CliFailure{long millis=java.time.Duration.between(Instant.now(),deadline).toMillis();if(millis<=0)throw new CliFailure("deadline_exceeded");return (int)Math.min(maximum,Math.max(1,(millis+999)/1000));}
    /** 公开结果只保存可回读的稳定错误事实，不回显外部自由文本。 */
    private JsonNode error(Exception failure){return failure instanceof CliFailure known?json.valueToTree(known.failure):json.createObjectNode().put("error",failure instanceof AuthorizationReadFailure?"browser_authorization_result_unknown":"browser_result_unknown");}
    /** 一级错误码不能覆盖上游已明确的未知副作用。 */
    private String failureStatus(Exception failure){return failure instanceof CliFailure known&&!known.failure.unknownEffect()?"failed":"unknown";}
    private static String readOutput(InputStream in){try(in){byte[] bytes=in.readNBytes(8*1024*1024+1);if(bytes.length>8*1024*1024)throw new IOException("browser_output_too_large");return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);}catch(IOException e){throw new UncheckedIOException(e);}}
    public JsonNode device(String tenant,String user,String operation,String device,String suppliedRequestId)throws Exception{
        return device(tenant,user,operation,device,suppliedRequestId,null);
    }
    public JsonNode device(String tenant,String user,String operation,String device,String suppliedRequestId,String connectionRequestId)throws Exception{
        if(!Set.of("pair","revoke").contains(operation))throw new IllegalArgumentException("browser_device_operation_invalid");
        if(operation.equals("revoke")&&(device==null||device.isBlank()||device.startsWith("-")))throw new IllegalArgumentException("browser_device_required");
        if(connectionRequestId!=null){BrowserConnectionGrantStore.requestId(connectionRequestId);if(!operation.equals("pair"))throw new IllegalArgumentException("browser_connection_pair_required");}
        String requestId=BrowserAuthorizationJournal.requestId(suppliedRequestId);
        Path home=home(tenant,user);daemon(home);
            journalControl(home,requestId,"service_control",operation,"started",device,null);
            try{
                observedGrants(home,20,requestId,"before_control");
                JsonNode result=run(home,operation.equals("pair")?List.of("--json","daemon","pair"):List.of("--json","daemon","revoke",device),20);
                if(connectionRequestId!=null){
                    // 固定官方格式是 public_url#credential；秘密只用于一次摘要计算，不进入关联账本。
                    String pairing=result.path("pairing_url").asText();int fragment=pairing.lastIndexOf('#');
                    String credential=fragment<0?null:pairing.substring(fragment+1);
                    if(credential==null||!credential.matches("[A-Za-z0-9_-]{43}"))throw new IOException("browser_connection_pairing_invalid");
                    connectionGrants.bind(home,connectionRequestId,hash(credential));
                }
                journalControl(home,requestId,"service_control",operation,"succeeded",device,null);
                observeAfterMutation(home,requestId);
                return result;
            }catch(Exception failure){
                try{journalControl(home,requestId,"service_control",operation,"unknown",device,null);}
                catch(IOException auditFailure){failure.addSuppressed(auditFailure);}
                throw failure;
            }
    }
    private void journalControl(Path home,String requestId,String source,String operation,String phase,String device,Integer status)throws IOException{
        synchronized(lock(home.resolve("authorization-audit"))){authorizationJournal.control(home,requestId,source,operation,phase,device,status);}
    }
    private JsonNode observedGrants(Path home,int seconds,String requestId,String source)throws Exception{
        synchronized(lock(home.resolve("authorization-observation"))){
            JsonNode grants=run(home,List.of("--json","daemon","devices"),seconds);
            synchronized(lock(home.resolve("authorization-audit"))){authorizationJournal.observe(home,grants,requestId,source);}
            return grants;
        }
    }
    private void observeAfterMutation(Path home,String requestId){
        try{observedGrants(home,20,requestId,"after_control");}
        catch(Exception failure){log.warn("浏览器授权变更后观察未完成 ownerTag={} requestId={} errorCode=authorization_observation_unknown",home.getFileName(),requestId);}
    }
    private Path proxyHome(String key)throws IOException{
        if(!key.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("browser_route_invalid");
        Path home=root.resolve("owners").resolve(key);
        if(!Files.exists(home.resolve("owner.json")))throw new FileNotFoundException("browser_owner_not_found");return home;
    }
    String authorizationExchangeStarted(String key,String action)throws IOException{
        Path home=proxyHome(key);String requestId=BrowserAuthorizationJournal.requestId(null);
        journalControl(home,requestId,"extension",action,"started",null,null);
        return requestId;
    }
    void authorizationExchangeFinished(String key,String requestId,String action,Integer status,byte[] response)throws IOException{
        Path home=proxyHome(key);String device=null;
        if(status!=null&&status>=200&&status<300&&response!=null){
            try{device=json.readTree(response).path("device_id").asText(null);}catch(IOException invalid){/* 仅观察白名单字段，原响应仍由官方扩展处理。 */}
        }
        // 配对响应必须及时交还扩展保存token；这里不查询CLI，也不等待控制调用的外部步骤。
        journalControl(home,requestId,"extension",action,status==null?"unknown":status>=200&&status<300?"succeeded":"rejected",device,status);
    }
    /** 在官方配对授权前仅按当前 owner 的原 Bearer 摘要建立待确认窗口。 */
    String connectionExchangeStarted(String key,String action,String authorization)throws IOException{
        if(!"pair".equals(action)||authorization==null||!authorization.startsWith("Bearer "))return null;
        String credential=authorization.substring("Bearer ".length());
        if(!credential.matches("[A-Za-z0-9_-]{43}"))return null;
        return connectionGrants.beginExchange(proxyHome(key),hash(credential));
    }
    /** 官方成功响应只提取 device_id，不保存正文或扩展的新凭据。 */
    void connectionExchangeFinished(String key,String requestId,Integer status,byte[] response)throws IOException{
        if(requestId==null)return;
        String device=null;
        if(status!=null&&status>=200&&status<300&&response!=null){
            try{JsonNode parsed=json.readTree(response);if(parsed!=null&&parsed.path("device_id").isTextual())device=parsed.path("device_id").textValue();}
            catch(IOException invalid){/* 官方成功但身份响应无法确认，保持 UNKNOWN。 */}
        }
        connectionGrants.finishExchange(proxyHome(key),requestId,status,device);
    }
    public BrowserConnectionGrant connectionGrant(String tenant,String user,String requestId)throws IOException{
        return connectionGrants.detail(home(tenant,user),requestId);
    }
    public BrowserDevices devices(String tenant,String user)throws Exception{
        Path home=home(tenant,user);daemon(home);JsonNode grants=observedGrants(home,20,null,"devices_read");
        JsonNode connected=run(home,List.of("--json","status"),20).path("browsers");Set<String> ids=new HashSet<>();connected.forEach(b->ids.add(b.path("instance_id").asText()));
        DevicePreferences prefs=preferences(home);
        var items=new ArrayList<BrowserDevices.Device>();grants.forEach(d->{String id=d.path("device_id").asText();String label=prefs.labels().stream().filter(l->l.deviceId().equals(id)).map(DeviceLabel::label).findFirst().orElse(d.path("label").asText());items.add(new BrowserDevices.Device(id,d.path("browser_id").asText(),label,d.path("expires_at").asText(),ids.contains(d.path("browser_id").asText())));});
        return new BrowserDevices(items,"https://github.com/Tencent/BrowserSkill/blob/"+BrowserCommandContract.COMMIT+"/AGENT_INSTALL.md#4-connect-the-browser-extension",prefs.selectedBrowser());
    }
    DevicePreferences preferences(Path home)throws IOException{Path path=home.resolve("device-preferences.json");return Files.exists(path)?read(path,DevicePreferences.class):new DevicePreferences(null,List.of());}
    public BrowserDevices preference(String tenant,String user,String deviceId,String label,boolean select)throws Exception{
        Path home=home(tenant,user);daemon(home);Path file=home.resolve("device-preferences.json");
        synchronized(lock(file)){
            BrowserDevices.Device device=devices(tenant,user).items().stream().filter(d->d.deviceId().equals(deviceId)).findFirst().orElseThrow(()->new IllegalArgumentException("browser_device_not_owned"));
            DevicePreferences prior=preferences(home);var names=new ArrayList<>(prior.labels());
            if(!select){if(label==null||label.isBlank()||label.length()>128)throw new IllegalArgumentException("browser_label_invalid");names.removeIf(n->n.deviceId().equals(deviceId));names.add(new DeviceLabel(deviceId,label.trim()));}
            write(file,new DevicePreferences(select?device.browserId():prior.selectedBrowser(),names));return devices(tenant,user);
        }
    }
    public BrowserReceipt execute(BrowserRequest request)throws Exception{
        Path home=home(request.tenant(),request.endUser());budget(request.deadlineAt(),150);Path receipt=home.resolve("receipts").resolve(hash(request.invocation())+".json");
        activeReceipts.add(receipt.toString());
        try{synchronized(lock(receipt)){
            var fingerprintNode=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(request);fingerprintNode.remove(List.of("deadline_at","deadlineAt"));String fingerprint=hash(json.writeValueAsString(fingerprintNode));
            if(Files.exists(receipt)){
                BrowserReceipt prior=read(receipt,BrowserReceipt.class);
                // 冲突请求没有执行权；原 invocation 的持久事实不能被另一组参数覆盖。
                if(!fingerprint.equals(prior.fingerprint()))return rejected(request,fingerprint,null,"browser_invocation_conflict");
                if(request.operation().equals("start")&&Set.of("prepared","starting","bound","unknown").contains(prior.status())){daemon(home,request.deadlineAt());return recoverStart(home,receipt,prior,request.deadlineAt());}
                if("submitted".equals(prior.status())&&!runningRecordings.contains(receipt.toString())){prior=prior.state("unknown",prior.result());write(receipt,prior);}return prior;
            }
            synchronized(lock(home.resolve("task-lock").resolve(hash(Objects.requireNonNullElse(request.task(),"start"))))){
            BrowserReceipt state;
            if(request.operation().equals("start")){
                String browser=((BrowserParameters.StartParameters)request.parameters()).browser();if(browser==null)browser=preferences(home).selectedBrowser();
                if(browser==null||browser.isBlank()||browser.startsWith("-")){
                    // 尚未派发浏览器操作；持久化明确拒绝，避免 HTTP 500 被宿主当作未知副作用。
                    state=new BrowserReceipt(fingerprint,request.invocation(),"start",null,request.session(),request.run(),Instant.now().toString(),"rejected",null,null,null,
                            json.createObjectNode().put("error","browser_selection_required")
                                    .put("message","请在会话端浏览器页面配对个人设备并选择默认浏览器，或指定已授权设备的 browser_id。"));
                    write(receipt,state);return state;
                }
                state=new BrowserReceipt(fingerprint,request.invocation(),"start",UUID.randomUUID().toString(),request.session(),request.run(),Instant.now().toString(),"prepared",(System.currentTimeMillis()+300000)+":"+UUID.randomUUID(),browser,null,null);
                daemon(home,request.deadlineAt());
                BrowserReceipt authorization=checkBrowserAuthorization(home,receipt,state,request.deadlineAt());if(authorization!=null)return authorization;
                Path tasks=home.resolve("tasks");Files.createDirectories(tasks);List<BrowserReceipt> existing=storedTasks(home);
                JsonNode sessions=null;
                if(existing.stream().anyMatch(candidate->"active".equals(candidate.status())))try{sessions=sessionSnapshot(home,request.deadlineAt());}
                catch(Exception failure){return bindingCheckUnknown(receipt,state,failure);}
                JsonNode observed=sessions;
                // 已明确失效的 active 不占新任务限额；未知、待启动及未确认清理的任务仍占用。
                long active=existing.stream().filter(candidate->Set.of("prepared","starting","bound","unknown").contains(candidate.status())
                        ||"active".equals(candidate.status())&&bindingPresent(observed,candidate.binding())).count();
                if(active>=5)return persistRejected(receipt,request,fingerprint,null,"browser_task_limit");
                write(tasks.resolve(state.task()+".json"),state);write(receipt,state);return start(home,receipt,state,request.deadlineAt());
            }
            BrowserReceipt task=null;Path output=home.resolve("outputs").resolve(hash(request.invocation()));
            var parameters=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(request.parameters());parameters.remove("_operation");
            List<String> args;
            // 这里只捕获本地封闭契约校验；尚未启动 daemon 或派发任何页面命令。
            try{
                task=requireTask(home,request.task());
                args=request.operation().equals("stop")?List.of("--json","session","stop",task.binding().sessionId()):BrowserCommandContract.arguments(request.operation(),parameters,task.binding().sessionId(),task.browser(),output);
            }catch(IllegalArgumentException rejection){return persistRejected(receipt,request,fingerprint,task,rejection.getMessage());}
            Files.createDirectories(output);daemon(home,request.deadlineAt());
            state=new BrowserReceipt(fingerprint,request.invocation(),request.operation(),request.task(),request.session(),request.run(),Instant.now().toString(),"submitted",null,task.browser(),task.binding(),null);
            if(!Set.of("stop","record-stop").contains(request.operation())){BrowserReceipt authorization=checkBrowserAuthorization(home,receipt,state,request.deadlineAt());if(authorization!=null)return authorization;}
            write(receipt,state);
            BrowserReceipt binding=checkTaskBinding(home,receipt,state,task,request.deadlineAt());if(binding!=null)return binding;
            if(request.operation().equals("record-start")){
                Path active=home.resolve("active-recording.json");
                synchronized(lock(active)){
                    if(Files.exists(active)){
                        RecordingBinding existing=read(active,RecordingBinding.class);BrowserReceipt previous=read(Path.of(existing.receipt()),BrowserReceipt.class);
                        if(!Set.of("succeeded","cancelled").contains(previous.status()))return persistRejected(receipt,request,fingerprint,task,"browser_recording_already_owned");
                    }
                    write(receipt,state);write(active,new RecordingBinding(receipt.toString(),null,request.task(),output.toString()));runningRecordings.add(receipt.toString());
                }
                budget(request.deadlineAt(),86430);BrowserReceipt pending=state;List<String> recordingArgs=args;Thread.ofVirtual().start(()->{try{BrowserReceipt ended=finish(home,receipt,pending,recordingArgs,86430,request.deadlineAt());if("unknown".equals(ended.status())&&!recordingCleanupPending(ended)&&!cancelled(home,pending.invocation())){boolean cleaned=false;try{run(home,List.of("--json","record","stop","--output",output.toString()),20);cleaned=true;}catch(Exception cleanup){ }write(receipt,ended.state("unknown",json.createObjectNode().put("error","browser_recording_result_unknown").put("cleanup_complete",cleaned)));}}catch(IOException persistence){throw new UncheckedIOException(persistence);}finally{runningRecordings.remove(receipt.toString());}});return pending;
            }
            if(request.operation().equals("record-stop")){
                Path active=home.resolve("active-recording.json");
                synchronized(lock(active)){
                    if(!Files.exists(active))return persistRejected(receipt,request,fingerprint,task,"browser_recording_not_found");
                    RecordingBinding recording=read(active,RecordingBinding.class);
                    if(!request.task().equals(recording.task()))return persistRejected(receipt,request,fingerprint,task,"browser_recording_owner_mismatch");
                    if(recording.stopReceipt()!=null)return persistRejected(receipt,request,fingerprint,task,"browser_recording_stop_already_submitted");
                    args=BrowserCommandContract.arguments("record-stop",parameters,task.binding().sessionId(),task.browser(),Path.of(recording.output()));
                    write(active,new RecordingBinding(recording.receipt(),receipt.toString(),recording.task(),recording.output()));
                }
            }
            BrowserReceipt completed=finish(home,receipt,state,args,request.operation().equals("request-help")?300:120,request.deadlineAt());
            if(request.operation().equals("stop")&&"succeeded".equals(completed.status()))write(home.resolve("tasks").resolve(task.task()+".json"),task.state("stopped",completed.result()));
            return completed;
            }
        }}finally{activeReceipts.remove(receipt.toString());}
    }
    private BrowserReceipt rejected(BrowserRequest request,String fingerprint,BrowserReceipt task,String reason){
        String[] failure=reason.split(":",2);var result=json.createObjectNode().put("error",failure[0]);
        if(failure.length==2)result.put("parameter",failure[1]);
        return new BrowserReceipt(fingerprint,request.invocation(),request.operation(),request.task(),request.session(),request.run(),Instant.now().toString(),"rejected",null,task==null?null:task.browser(),task==null?null:task.binding(),result);
    }
    private BrowserReceipt persistRejected(Path receipt,BrowserRequest request,String fingerprint,BrowserReceipt task,String reason)throws IOException{
        BrowserReceipt state=rejected(request,fingerprint,task,reason);write(receipt,state);return state;
    }
    /** daemon 会话仅存于内存；持久任务的 active 不能代替当前冻结绑定的存在证据。 */
    private BrowserReceipt checkTaskBinding(Path home,Path receipt,BrowserReceipt state,BrowserReceipt task,Instant deadline)throws IOException{
        try{
            if(cancelled(home,state.invocation()))return read(receipt,BrowserReceipt.class);
            JsonNode sessions=sessionSnapshot(home,deadline);
            if(cancelled(home,state.invocation()))return read(receipt,BrowserReceipt.class);
            if(bindingPresent(sessions,task.binding()))return null;
        }catch(Exception unknown){
            if(cancelled(home,state.invocation()))return read(receipt,BrowserReceipt.class);
            if(unknown instanceof InterruptedException)Thread.currentThread().interrupt();
            return bindingCheckUnknown(receipt,state,unknown);
        }
        // 只证明原绑定不可续接；没有执行 stop，也不能宣称旧窗口与借用页已经清理。
        var lost=json.createObjectNode().put("error","browser_task_binding_lost").put("cleanup_complete",false);
        write(home.resolve("tasks").resolve(task.task()+".json"),task.state("inactive",lost));
        BrowserReceipt denied=state.state("rejected",lost);write(receipt,denied);return denied;
    }
    private BrowserReceipt bindingCheckUnknown(Path receipt,BrowserReceipt state,Exception failure)throws IOException{
        if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
        BrowserReceipt pending=failure instanceof CliFailure known&&"deadline_exceeded".equals(known.code)?state.state("failed",error(failure))
                :state.state("unknown",json.createObjectNode().put("error","browser_task_binding_check_unknown"));
        write(receipt,pending);return pending;
    }
    private JsonNode sessionSnapshot(Path home,Instant deadline)throws Exception{
        JsonNode snapshot=run(home,List.of("--json","session","list"),budget(deadline,5));
        // 官方 CLI 将 IPC 的 reply.sessions 直接打印成顶层数组，不保留 IPC 包装对象。
        if(snapshot==null||!snapshot.isArray())throw new IOException("browser_session_snapshot_invalid");
        for(JsonNode session:snapshot){
            if(!session.path("session_id").isTextual()||session.path("session_id").asText().isBlank()
                    ||!session.path("browser_instance_id").isTextual()||session.path("browser_instance_id").asText().isBlank()
                    ||session.hasNonNull("agent_window_id")&&(!session.path("agent_window_id").isIntegralNumber()||!session.path("agent_window_id").canConvertToLong()))throw new IOException("browser_session_snapshot_invalid");
        }
        return snapshot;
    }
    private boolean bindingPresent(JsonNode sessions,BrowserBinding binding){
        if(binding==null||binding.sessionId()==null||binding.browserInstanceId()==null||binding.agentWindowId()==null)return false;
        for(JsonNode session:sessions)if(binding.sessionId().equals(session.path("session_id").asText())
                &&binding.browserInstanceId().equals(session.path("browser_instance_id").asText())
                &&session.path("agent_window_id").isIntegralNumber()&&binding.agentWindowId()==session.path("agent_window_id").longValue())return true;
        return false;
    }
    private Path cancellation(Path home,String invocation){return home.resolve("receipts").resolve(hash(invocation+":cancel")+".json");}
    private boolean cancelled(Path home,String invocation){return Files.exists(cancellation(home,invocation));}
    public BrowserReceipt cancel(String tenant,String user,String invocation)throws Exception{
        Path home=home(tenant,user),receipt=home.resolve("receipts").resolve(hash(invocation)+".json"),cancel=cancellation(home,invocation);
        synchronized(lock(cancel)){
            BrowserReceipt original=read(receipt,BrowserReceipt.class);
            BrowserReceipt intent=new BrowserReceipt(null,invocation+":cancel","cancel",original.task(),original.session(),original.run(),Instant.now().toString(),"cancelling",original.requestToken(),original.browser(),original.binding(),null);
            if(Files.exists(cancel)){BrowserReceipt prior=read(cancel,BrowserReceipt.class);if("cancelled".equals(prior.status()))return prior;intent=prior.state("cancelling",null);}
            write(cancel,intent);
            try{
                daemon(home);
                interruptOwned(home,original);
                if("record-start".equals(original.operation())){
                    RecordingBinding recording=read(home.resolve("active-recording.json"),RecordingBinding.class);
                    if(!receipt.toString().equals(recording.receipt()))throw new CliFailure("browser_recording_owner_mismatch");
                    run(home,List.of("--json","record","stop","--output",recording.output()),30);
                }else if(original.requestToken()!=null){
                    JsonNode cleanup=run(home,List.of("--json","session","request",original.requestToken(),"--cancel"),30);
                    String phase=cleanup.path("state").asText();
                    if("cleanup_failed".equals(phase))throw new CliFailure("browser_cleanup_failed");
                    if(!"closed".equals(phase))throw new IOException("browser_cleanup_pending");
                }else if(original.binding()!=null)run(home,List.of("--json","session","stop",original.binding().sessionId()),30);
                else throw new IOException("browser_cleanup_target_unknown");
                intent=intent.state("cancelled",json.createObjectNode().put("cleanup_complete",true));
                write(receipt,original.state("cancelled",intent.result()));
                if(original.task()!=null&&Files.exists(home.resolve("tasks").resolve(original.task()+".json"))){Path task=home.resolve("tasks").resolve(original.task()+".json");write(task,read(task,BrowserReceipt.class).state("stopped",intent.result()));}
            }catch(Exception failure){intent=intent.state("failed".equals(failureStatus(failure))?"cleanup_failed":"unknown",error(failure));}
            write(cancel,intent);return intent;
        }
    }
    private static final class AuthorizationReadFailure extends IOException{AuthorizationReadFailure(){super("browser_authorization_result_unknown");}}
    /** 通过官方 daemon 查询当前 owner 的有效授权，不依赖扩展仍在线的连接状态。 */
    private boolean browserAuthorized(Path home,String browser,Instant deadline)throws Exception{
        int seconds=budget(deadline,20);JsonNode grants;
        try{grants=run(home,List.of("--json","daemon","devices"),seconds);}
        catch(Exception failure){throw new AuthorizationReadFailure();}
        if(!grants.isArray())throw new AuthorizationReadFailure();
        // 官方 devices 保留过期条目；列表存在不能代替公开到期时间的校验。
        for(JsonNode device:grants)if(browser.equals(device.path("browser_id").asText())){
            try{return Instant.parse(device.path("expires_at").asText()).isAfter(Instant.now());}
            catch(java.time.format.DateTimeParseException invalid){throw new AuthorizationReadFailure();}
        }
        return false;
    }
    private void requireBrowserAuthorization(Path home,String browser,Instant deadline)throws Exception{
        if(!browserAuthorized(home,browser,deadline))throw new CliFailure("browser_not_authorized");
    }
    private BrowserReceipt checkBrowserAuthorization(Path home,Path receipt,BrowserReceipt state,Instant deadline)throws IOException{
        try{requireBrowserAuthorization(home,state.browser(),deadline);return null;}
        catch(Exception failure){
            if(failure instanceof CliFailure known&&"browser_not_authorized".equals(known.code))return authorizationDenied(home,receipt,state);
            state=failure instanceof CliFailure?state.state("failed",error(failure)):state.state("unknown",json.createObjectNode().put("error","browser_authorization_result_unknown"));write(receipt,state);return state;
        }
    }
    private BrowserReceipt authorizationDenied(Path home,Path receipt,BrowserReceipt state)throws IOException{
        state=state.state("failed",json.createObjectNode().put("error","browser_not_authorized"));write(receipt,state);
        if("start".equals(state.operation()))write(home.resolve("tasks").resolve(state.task()+".json"),state);
        return state;
    }
    private BrowserReceipt start(Path home,Path receipt,BrowserReceipt state,Instant deadline)throws IOException{
        try{
            if(cancelled(home,state.invocation()))return read(receipt,BrowserReceipt.class);
            requireBrowserAuthorization(home,state.browser(),deadline);
            run(home,List.of("--json","session","request",state.requestToken(),"--prepare"),budget(deadline,45));
            if(cancelled(home,state.invocation()))return read(receipt,BrowserReceipt.class);
            state=state.state("starting",null);write(receipt,state);
            requireBrowserAuthorization(home,state.browser(),deadline);
            JsonNode result=run(home,List.of("--json","session","start","--request-id",state.requestToken(),"--browser",state.browser()),budget(deadline,50));
            state=state.bound(binding(result));if(cancelled(home,state.invocation())){run(home,List.of("--json","session","stop",state.binding().sessionId()),budget(deadline,30));return read(receipt,BrowserReceipt.class);}write(receipt,state);return claim(home,receipt,state,deadline);
        }catch(Exception failure){if(cancelled(home,state.invocation()))return read(receipt,BrowserReceipt.class);state=state.state(failureStatus(failure),error(failure));if(cancelled(home,state.invocation()))return read(receipt,BrowserReceipt.class);write(receipt,state);write(home.resolve("tasks").resolve(state.task()+".json"),state);return state;}
    }
    private BrowserBinding binding(JsonNode node){String id=node.path("session_id").asText();if(id.isBlank())throw new IllegalArgumentException("browser_binding_missing");return new BrowserBinding(id,node.path("browser_instance_id").asText(),node.path("agent_window_id").isNumber()?node.path("agent_window_id").longValue():null);}
    private BrowserReceipt recoverStart(Path home,Path receipt,BrowserReceipt state,Instant deadline)throws Exception{
        BrowserReceipt authorization=checkBrowserAuthorization(home,receipt,state,deadline);if(authorization!=null)return authorization;
        JsonNode status=run(home,List.of("--json","session","request",state.requestToken()),budget(deadline,45));String phase=status.path("state").asText();
        if(Set.of("ready","active").contains(phase)&&status.path("session").isObject()){state=state.bound(binding(status.path("session")));write(receipt,state);return claim(home,receipt,state,deadline);}
        if("prepared".equals(phase))return start(home,receipt,state,deadline);
        state=state.state("unknown",status);write(receipt,state);return state;
    }
    private BrowserReceipt claim(Path home,Path receipt,BrowserReceipt state,Instant deadline)throws Exception{
        if(cancelled(home,state.invocation()))return read(receipt,BrowserReceipt.class);
        write(home.resolve("tasks").resolve(state.task()+".json"),state);
        BrowserReceipt authorization=checkBrowserAuthorization(home,receipt,state,deadline);if(authorization!=null)return authorization;
        run(home,List.of("--json","session","request",state.requestToken(),"--claim"),budget(deadline,45));
        state=state.state("active",null);if(cancelled(home,state.invocation()))return read(receipt,BrowserReceipt.class);write(receipt,state);write(home.resolve("tasks").resolve(state.task()+".json"),state);return state;
    }
    private BrowserReceipt finish(Path home,Path receipt,BrowserReceipt state,List<String> args,int maximum,Instant deadline){
        try{
            if(cancelled(home,state.invocation()))return read(receipt,BrowserReceipt.class);
            if(!Set.of("stop","record-stop").contains(state.operation()))requireBrowserAuthorization(home,state.browser(),deadline);
            boolean navigation=Set.of("click","press").contains(state.operation());
            TabObservation before=navigation?observeTabs(home,state,deadline):null;
            if(cancelled(home,state.invocation()))return read(receipt,BrowserReceipt.class);
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("browser_command_interrupted");
            // wait-ms 不接受 CLI session；仍按已验证的原任务会话登记执行归属，不能退成整个 owner 的 control。
            JsonNode result="wait-ms".equals(state.operation())
                    ?runInSession(home,args,budget(deadline,maximum),state.binding().sessionId())
                    :run(home,args,budget(deadline,maximum));
            if(result.isObject()){var safe=((com.fasterxml.jackson.databind.node.ObjectNode)result).deepCopy();safe.remove(List.of("path","output"));result=safe;}
            boolean succeeded=!(result.path("ok").isBoolean()&&!result.path("ok").booleanValue());
            // 观察只是成功操作后的附加证据；失败或取消均不能重放点击，也不能覆盖原点击结果。
            if(navigation&&succeeded&&!cancelled(home,state.invocation())){
                TabObservation after=observeTabs(home,state,deadline);
                var enriched=result.isObject()?((com.fasterxml.jackson.databind.node.ObjectNode)result).deepCopy():json.createObjectNode();
                if(!result.isObject())enriched.set("command_result",result);
                JsonNode observation;
                try{observation=navigationObservation(state,args,result,before,after);}
                catch(RuntimeException invalid){observation=json.createObjectNode().put("status","unconfirmed").put("candidate_only",true).put("after_error","browser_navigation_observation_invalid");}
                enriched.set("navigation",observation);result=enriched;
            }
            state=state.state(succeeded?"succeeded":"failed",result);
        }
        catch(RecordingCleanupPending pending){
            var trace=((com.fasterxml.jackson.databind.node.ObjectNode)pending.trace).deepCopy();trace.remove(List.of("path","output"));
            trace.put("error","browser_recording_cleanup_pending");state=state.state("unknown",trace);
        }
        catch(Exception failure){state=state.state(failureStatus(failure),error(failure));}
        try{if(cancelled(home,state.invocation()))return read(receipt,BrowserReceipt.class);write(receipt,state);
            if(state.operation().startsWith("record-")){reconcileRecording(home);return read(receipt,BrowserReceipt.class);}
        }catch(IOException e){throw new UncheckedIOException(e);}return state;
    }
    private record TabObservation(JsonNode tabs,String error){}
    /** 只观察冻结会话的 Agent 窗口，不因新增页产生控制授权；两侧各自最多占用剩余期限中的五秒。 */
    private TabObservation observeTabs(Path home,BrowserReceipt state,Instant deadline){
        if(cancelled(home,state.invocation()))return new TabObservation(null,"browser_navigation_cancelled");
        if(Thread.currentThread().isInterrupted())return new TabObservation(null,"browser_navigation_interrupted");
        if(state.binding()==null||state.binding().agentWindowId()==null)return new TabObservation(null,"browser_navigation_window_unbound");
        try{
            JsonNode observed=run(home,List.of("--json","tab","list","--session",state.binding().sessionId(),"--scope=agent"),budget(deadline,5));
            if(!observed.path("tabs").isArray())throw new CliFailure("browser_navigation_observation_invalid");
            var tabs=json.createArrayNode();
            for(JsonNode tab:observed.path("tabs")){
                if(!tab.path("window_id").isIntegralNumber()||!tab.path("window_id").canConvertToLong())throw new CliFailure("browser_navigation_observation_invalid");
                if(tab.path("window_id").longValue()!=state.binding().agentWindowId()||!"agent".equals(tab.path("scope").asText()))continue;
                if(!tab.path("tab_id").isIntegralNumber()||!tab.path("tab_id").canConvertToLong())throw new CliFailure("browser_navigation_observation_invalid");
                for(String field:List.of("url","title"))if(tab.hasNonNull(field)&&!tab.get(field).isTextual())throw new CliFailure("browser_navigation_observation_invalid");
                if(tab.hasNonNull("active")&&!tab.path("active").isBoolean())throw new CliFailure("browser_navigation_observation_invalid");
                var safe=json.createObjectNode();
                for(String field:List.of("tab_id","url","title","active","window_id","scope"))if(tab.has(field))safe.set(field,tab.get(field));
                tabs.add(safe);
            }
            return new TabObservation(tabs,null);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return new TabObservation(null,"browser_navigation_interrupted");}
        catch(Exception failure){return new TabObservation(null,failure instanceof CliFailure known?known.code:"browser_navigation_observation_unknown");}
    }
    private JsonNode navigationObservation(BrowserReceipt state,List<String> args,JsonNode commandResult,TabObservation before,TabObservation after){
        var result=json.createObjectNode().put("status",before.tabs()!=null&&after.tabs()!=null?"observed":before.tabs()!=null||after.tabs()!=null?"partial":"unconfirmed").put("candidate_only",true);
        if(state.binding()!=null&&state.binding().agentWindowId()!=null)result.put("agent_window_id",state.binding().agentWindowId());
        if(before.error()!=null)result.put("before_error",before.error());
        if(after.error()!=null)result.put("after_error",after.error());
        Long source=commandResult.path("tab_id").isIntegralNumber()&&commandResult.path("tab_id").canConvertToLong()?commandResult.path("tab_id").longValue():null;
        if(source==null)for(String arg:args)if(arg.startsWith("--tab-id=")){source=Long.valueOf(arg.substring("--tab-id=".length()));break;}
        if(source==null)source=activeTab(before.tabs());
        if(source!=null)result.put("source_tab_id",source);
        Long active=activeTab(after.tabs());if(active!=null)result.put("active_tab_id",active);
        JsonNode original=tab(before.tabs(),source),current=tab(after.tabs(),source);
        if(original!=null)result.set("source_before",original);
        if(current!=null)result.set("source_after",current);
        if(original!=null&&current!=null&&original.path("url").isTextual()&&current.path("url").isTextual())result.put("source_url_changed",!original.path("url").equals(current.path("url")));
        var added=json.createArrayNode();
        // 没有成功的前置观察就不能把现有页宣称为本次操作新增，只返回后置活动页和原页事实。
        if(before.tabs()!=null&&after.tabs()!=null)for(JsonNode candidate:after.tabs())if(tab(before.tabs(),candidate.path("tab_id").longValue())==null)added.add(candidate);
        result.set("new_tabs",added);return result;
    }
    private Long activeTab(JsonNode tabs){
        Long active=null;if(tabs==null)return null;
        for(JsonNode tab:tabs)if(tab.path("active").asBoolean()){
            if(active!=null)return null;active=tab.path("tab_id").longValue();
        }
        return active;
    }
    private JsonNode tab(JsonNode tabs,Long id){
        if(tabs==null||id==null)return null;
        for(JsonNode tab:tabs)if(tab.path("tab_id").longValue()==id)return tab;return null;
    }
    private boolean recordingCleanupPending(BrowserReceipt receipt){
        return "unknown".equals(receipt.status())&&receipt.result()!=null
            &&receipt.result().path("error").asText().equals("browser_recording_cleanup_pending");
    }
    /** 对同一录制的准确两端归并；真实成功端和完全相同 trace 才能证明竞争端已清理。 */
    void reconcileRecording(Path home)throws IOException{
        Path active=home.resolve("active-recording.json");
        synchronized(lock(active)){
            if(!Files.exists(active))return;
            RecordingBinding recording=read(active,RecordingBinding.class);
            if(recording.stopReceipt()==null||!Files.exists(Path.of(recording.receipt()))||!Files.exists(Path.of(recording.stopReceipt())))return;
            BrowserReceipt start=read(Path.of(recording.receipt()),BrowserReceipt.class),stop=read(Path.of(recording.stopReceipt()),BrowserReceipt.class);
            if(cancelled(home,start.invocation())||cancelled(home,stop.invocation()))return;
            if(!start.operation().equals("record-start")||!stop.operation().equals("record-stop")
                    ||!recording.task().equals(start.task())||!recording.task().equals(stop.task())
                    ||!Objects.equals(start.binding(),stop.binding()))return;
            confirmRecordingPeer(Path.of(recording.receipt()),start,stop,recording.output());
            confirmRecordingPeer(Path.of(recording.stopReceipt()),stop,start,recording.output());
        }
    }
    private void confirmRecordingPeer(Path path,BrowserReceipt pending,BrowserReceipt peer,String output)throws IOException{
        if(!recordingCleanupPending(pending)||!"succeeded".equals(peer.status())||peer.result()==null
                ||peer.result().has("cleanup_confirmed_by")||peer.result().has("error"))return;
        String tracePath=Path.of(output).resolve("trace.json").toString();
        if(!pending.result().path("trace_json").asText().equals(tracePath)||!peer.result().path("trace_json").asText().equals(tracePath)
                ||!pending.result().path("trace").isObject()||!pending.result().path("trace").equals(peer.result().path("trace")))return;
        var confirmed=((com.fasterxml.jackson.databind.node.ObjectNode)pending.result()).deepCopy();
        confirmed.remove("error");confirmed.put("cleanup_confirmed_by",peer.invocation());write(path,pending.state("succeeded",confirmed));
    }
    BrowserReceipt requireTask(Path home,String task)throws IOException{
        if(task==null||!task.matches(TASK_ID_PATTERN))throw new IllegalArgumentException("browser_task_invalid");
        BrowserReceipt receipt;
        try{receipt=read(home.resolve("tasks").resolve(task+".json"),BrowserReceipt.class);}
        catch(NoSuchFileException missing){throw new IllegalArgumentException("browser_task_not_found");}
        if(!"active".equals(receipt.status()))throw new IllegalArgumentException("browser_task_not_active");
        if(receipt.binding()==null||receipt.binding().sessionId()==null||receipt.binding().sessionId().isBlank()
                ||receipt.binding().browserInstanceId()==null||receipt.binding().browserInstanceId().isBlank()||receipt.binding().agentWindowId()==null)throw new IllegalArgumentException("browser_task_binding_missing");
        return receipt;
    }
    /** 当前任务目录投影与 invocation 回执分离，查询不会改写历史执行事实。 */
    public List<BrowserReceipt> taskStates(String tenant,String user)throws Exception{
        Path home=home(tenant,user);List<BrowserReceipt> states=storedTasks(home);
        if(states.stream().noneMatch(state->"active".equals(state.status())))return states.stream().map(this::taskStateView).toList();
        JsonNode sessions;
        try{Instant deadline=Instant.now().plusSeconds(10);daemon(home,deadline);sessions=sessionSnapshot(home,deadline);}
        catch(Exception failure){
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            return states.stream().map(state->taskStateView("active".equals(state.status())?state.state("unknown",json.createObjectNode().put("error","browser_task_binding_check_unknown")):state)).toList();
        }
        return states.stream().map(state->taskStateView("active".equals(state.status())&&!bindingPresent(sessions,state.binding())?state.state("inactive",json.createObjectNode().put("error","browser_task_binding_lost").put("cleanup_complete",false)):state)).toList();
    }
    private List<BrowserReceipt> storedTasks(Path home)throws IOException{
        Path directory=home.resolve("tasks");if(!Files.exists(directory))return List.of();
        try(var files=Files.list(directory)){return files.filter(path->path.getFileName().toString().matches(TASK_ID_PATTERN+"\\.json")).sorted().map(path->{try{return read(path,BrowserReceipt.class);}catch(IOException failed){throw new UncheckedIOException(failed);}}).toList();}
    }
    private BrowserReceipt taskStateView(BrowserReceipt state){
        var result=json.createObjectNode();
        if(state.result()!=null){
            JsonNode failure=state.result().path("error");if(failure.isTextual()&&failure.asText().matches("[a-z0-9_]{1,96}"))result.set("error",failure);
            if(state.result().path("cleanup_complete").isBoolean())result.set("cleanup_complete",state.result().path("cleanup_complete"));
        }
        return state.state(state.status(),result.isEmpty()?null:result).publicView();
    }
    public List<BrowserReceipt> receipts(String tenant,String user)throws IOException{
        Path dir=home(tenant,user).resolve("receipts");if(!Files.exists(dir))return List.of();
        // 原子文件替换允许无锁查询，不能等待执行锁而阻塞用户取消入口。
        try(var files=Files.list(dir)){return files.filter(p->p.toString().endsWith(".json")).sorted().map(p->{try{var state=read(p,BrowserReceipt.class);if("submitted".equals(state.status())&&!activeReceipts.contains(p.toString())&&!runningRecordings.contains(p.toString()))state=state.state("unknown",state.result());return state.publicView();}catch(IOException e){throw new UncheckedIOException(e);}}).toList();}
    }
    public byte[] screenshot(String tenant,String user,String invocation)throws IOException{
        Path home=home(tenant,user);var receipt=read(home.resolve("receipts").resolve(hash(invocation)+".json"),BrowserReceipt.class);
        if(!"succeeded".equals(receipt.status())||!"screenshot".equals(receipt.operation()))throw new FileNotFoundException("browser_screenshot_unavailable");return Files.readAllBytes(home.resolve("outputs").resolve(hash(invocation)).resolve("screenshot.png"));
    }
    <T>T read(Path path,Class<T> type)throws IOException{return json.readValue(Files.readAllBytes(path),type);}
    void write(Path path,Object value)throws IOException{
        Files.createDirectories(path.getParent());Path temp=Files.createTempFile(path.getParent(),".receipt-",".tmp");
        try{Files.write(temp,json.writeValueAsBytes(value));try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
    }
    @PreDestroy public void close(){daemons.values().forEach(Process::destroy);}
}
