package com.skillforge.institution.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.runtime.adapter.model.OpenAiModelPort;
import com.skillforge.institution.integration.LiveGradleAttemptMeter;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.*;
import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.media.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 只读已选模型；实际图片准备与 ModelPort/Codec 外调，账本许可是明确的隔离夹具。 */
class ModelVisionLiveAcceptance {
    @org.junit.jupiter.api.extension.RegisterExtension
    static final LiveGradleAttemptMeter.Outcome liveMeterOutcome = new LiveGradleAttemptMeter.Outcome();
    @org.junit.jupiter.api.AfterEach void collectLiveAttemptEvidence() { LiveGradleAttemptMeter.visionSnapshot(); }
    private static final String EPHEMERAL_SECRET=UUID.randomUUID().toString();
    record RouteEvidence(String mode,String provider,String service,String model,String endpoint,
            String visionDocumentation,String endpointDocumentation,String budgetBasis,
            int tileSize,int baseTokens,int tileTokens,long maxImageBytes,long maxRequestBytes,int maxDimension,
            String permissionBoundary) { }
    private static final ObjectMapper JSON=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    record Evidence(String status,String reason,String modelId,long revision,String providerModel,
            String expectedShape,String expectedColor,String expectedCode,String actualShape,String actualColor,String actualCode,
            Integer sourceWidth,Integer sourceHeight,Integer requestWidth,Integer requestHeight,
            Long inputTokens,Long outputTokens,String usageSource) { }
    @Test void selectedVisionModelConsumesRandomPixels() throws Exception {
        runScene(false);
        runScene(true);
    }
    private void runScene(boolean toolImage) throws Exception {
        String status="BLOCKED",reason="SOURCE_NOT_READ"; PlatformModelConfig model=null;
        Evidence evidence=null;
        try {
            model="explicit".equals(required("LIVE_VISION_MODE"))?explicitSource():source();
            if(!model.enabled()||!"LLM".equals(model.kind())||!model.imagePolicy().enabled())
                throw new IllegalStateException("SELECTED_VISION_POLICY_NOT_ENABLED");
            if(model.apiKeyEncrypted()==null||model.apiKeyEncrypted().isBlank())throw new IllegalStateException("SELECTED_CREDENTIAL_MISSING");
            var properties=new SkillForgeProperties();properties.setSecretKey(secretKey());
            var crypto=new CryptoService(properties);
            // 占位凭据绝不发送；不从环境 LLM_API_KEY 回退或切换路由。
            String credential=crypto.decrypt(model.apiKeyEncrypted());
            if(credential.isBlank()||credential.contains("your-")||credential.contains("placeholder"))
                throw new IllegalStateException("SELECTED_CREDENTIAL_PLACEHOLDER");
            var random=new SecureRandom();
            String shape=random.nextBoolean()?"circle":"square";
            String color=random.nextBoolean()?"red":"blue";
            String code=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
            var pixels=new BufferedImage(1200,800,BufferedImage.TYPE_INT_RGB);
            var g=pixels.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,1200,800);
            g.setColor(color.equals("red")?Color.RED:Color.BLUE);
            if(shape.equals("circle"))g.fillOval(400,80,400,400);else g.fillRect(400,80,400,400);
            g.setColor(Color.BLACK);g.setFont(new Font(Font.MONOSPACED,Font.BOLD,130));g.drawString(code,365,680);g.dispose();
            var encoded=SessionMediaImages.encode(pixels,"image/png");
            var tenant=new TenantId(required("LIVE_VISION_SOURCE_TENANT_ID"));
            var owner=SessionOwner.user(new SessionRef(tenant,"vision-isolated"),new AgentId("vision"),new EndUserId(1));
            var media=new Pixels(owner.sessionRef(),encoded);
            var variant=media.prepare(owner.sessionRef(),media.source,model.imagePolicy());
            var strategy=new RequestedModelStrategy("vision-live","1");
            var target=new FrozenModelTarget(new ModelBindingRef(tenant,"vision-isolated",model.revision()),model.modelId(),
                    new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT),false,false,new ResultRef("vision-source"),model.imagePolicy()),
                    Duration.ofSeconds(model.timeoutSeconds()),new ResultRef("vision-source"));
            var binding=new OpenAiModelPort.Binding(target,strategy,model.endpoint(),model.apiKeyEncrypted(),
                    new OpenAiModelPort.ProviderProfile(model.providerId(),model.serviceId(),model.profileVersion(),
                            ModelProtocol.fromId(model.protocol()),AuthenticationScheme.fromId(model.authenticationScheme()),model.dialectVersion()));
            try (var http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            var clock=Clock.systemUTC();
            var port=new OpenAiModelPort(ref->ref.equals(target.binding())?Optional.of(binding):Optional.empty(),
                    encrypted->crypto.decrypt(encrypted).getBytes(StandardCharsets.UTF_8),call->true,(call,output)->true,
                    http,clock,100000,ModelProviderRuntime.runtime(http,clock,100000),LiveGradleAttemptMeter.vision(model.contextWindowTokens()-128,128),media);
            var result=port.generate(call(owner,strategy,target,variant,toolImage));
            if(!(result instanceof ModelResult.Completed completed)) {
                reason=result instanceof ModelResult.Failed f?f.failure().reasonCode():"VISION_OUTCOME_UNKNOWN";
                status="FAILED";throw new IllegalStateException(reason);
            }
            String reply=JSON.readTree(completed.output().canonicalJson()).asText();
            var actual=JSON.readTree(reply.replace("```json","").replace("```","").strip());
            status=shape.equals(actual.path("shape").asText())&&color.equals(actual.path("color").asText())
                    &&code.equals(actual.path("code").asText())?"PASSED":"FAILED";
            reason=status.equals("PASSED")?null:"PIXEL_FACTS_MISMATCH";
            evidence=new Evidence(status,reason,model.id(),model.revision(),model.modelId(),shape,color,code,
                    actual.path("shape").asText(),actual.path("color").asText(),actual.path("code").asText(),
                    media.source.width(),media.source.height(),variant.width(),variant.height(),
                    completed.usage().inputTokens().isPresent()?completed.usage().inputTokens().getAsLong():null,
                    completed.usage().outputTokens().isPresent()?completed.usage().outputTokens().getAsLong():null,"PROVIDER_REPORTED");
            assertEquals("PASSED",status,"VISION_SEE_SAFE_EVIDENCE");
            }
        } catch(IllegalStateException identified) { reason=identified.getMessage();throw new AssertionError("VISION_SEE_SAFE_EVIDENCE"); }
        catch(Exception unavailable) {reason="VISION_STAGE_FAILED_"+unavailable.getClass().getSimpleName();throw new AssertionError("VISION_SEE_SAFE_EVIDENCE");}
        finally {
            if(evidence==null)evidence=new Evidence(status,reason,model==null?"explicit".equals(System.getenv("LIVE_VISION_MODE"))?"explicit-isolated":required("LIVE_VISION_SOURCE_MODEL_ID"):model.id(),
                    model==null?"explicit".equals(System.getenv("LIVE_VISION_MODE"))?1:Long.parseLong(required("LIVE_VISION_SOURCE_MODEL_REVISION")):model.revision(),
                    model==null?null:model.modelId(),null,null,null,null,null,null,null,null,null,null,null,null,null);
            Path dir=Path.of(required("LIVE_VISION_EVIDENCE_DIR"));Files.createDirectories(dir);
            Files.writeString(dir.resolve(toolImage?"vision-tool-evidence.json":"vision-evidence.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        }
    }
    private static PlatformModelConfig source() {
        try(var connection=DriverManager.getConnection(required("SPRING_DATASOURCE_URL"),required("SPRING_DATASOURCE_USERNAME"),required("SPRING_DATASOURCE_PASSWORD"))) {
            connection.setReadOnly(true);connection.setAutoCommit(false);
            try(var statement=connection.createStatement()){statement.execute("SET TRANSACTION READ ONLY");}
            try(var bind=connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {
                bind.setString(1,required("LIVE_VISION_SOURCE_TENANT_ID"));bind.execute();
            }
            try(var query=connection.prepareStatement("SELECT v.config::text FROM nw_tenant_model_selections s JOIN nw_platform_models m ON m.id=s.llm_model_config_id JOIN nw_platform_model_versions v ON v.model_id=m.id AND v.revision=m.current_revision WHERE s.tenant_id=CAST(? AS bigint) AND m.id=CAST(? AS bigint) AND m.current_revision=?")) {
                query.setString(1,required("LIVE_VISION_SOURCE_TENANT_ID"));query.setString(2,required("LIVE_VISION_SOURCE_MODEL_ID"));query.setLong(3,Long.parseLong(required("LIVE_VISION_SOURCE_MODEL_REVISION")));
                try(var rows=query.executeQuery()) {
                    if(!rows.next())throw new IllegalStateException("SELECTED_ROUTE_MISSING_OR_CHANGED");
                    String raw=rows.getString(1);
                    if(!JSON.readTree(raw).hasNonNull("image_policy"))throw new IllegalStateException("SELECTED_IMAGE_POLICY_MISSING");
                    var result=JSON.readValue(raw,PlatformModelConfig.class);connection.rollback();return result;
                }
            }
        }catch(IllegalStateException identified){throw identified;}
        catch(Exception unavailable){throw new IllegalStateException("SOURCE_READ_FAILED_"+unavailable.getClass().getSimpleName());}
    }
    private static String secretKey() {
        return "explicit".equals(System.getenv("LIVE_VISION_MODE"))?EPHEMERAL_SECRET:required("SECRET_KEY");
    }
    private static PlatformModelConfig explicitSource() throws Exception {
        String endpoint=required("LIVE_VISION_ENDPOINT");
        String service=endpoint.startsWith("https://dashscope.aliyuncs.com/")?"bailian-cn-beijing":"bailian-cn-beijing-workspace";
        String profile=service.endsWith("workspace")?"2026-09-13-workspace-v1":"2026-09-15-global-v1";
        // 官方Qwen3-VL估算单位32x32；额外base=1为验收保守预留，不声称是供应商收费常数。
        var policy=new ModelImagePolicy(true,true,1,1024*1024,2L*1024*1024,1024,32,1,1,"bailian-qwen3-vl-doc-20261002");
        var properties=new SkillForgeProperties();properties.setSecretKey(EPHEMERAL_SECRET);
        var encrypted=new CryptoService(properties).encrypt(required("LIVE_VISION_API_KEY"));
        var route=new RouteEvidence("explicit","aliyun-bailian",service,"qwen3-vl-plus",endpoint,
                "https://help.aliyun.com/zh/model-studio/vision",
                "https://help.aliyun.com/zh/model-studio/qwen-vl-compatible-with-openai",
                "Official estimate: 32x32 pixels per visual token; actual billing uses reported usage. Isolated limits: 1MiB/image,2MiB/request,1024px; base=1 conservative reserve.",
                32,1,1,policy.maxImageBytes(),policy.maxRequestBytes(),policy.maxDimension(),
                "In-memory media; fixture permit and guards; production image preparation,ModelPort,Provider Codec; no shared DB writes");
        var dir=Path.of(required("LIVE_VISION_EVIDENCE_DIR"));Files.createDirectories(dir);
        Files.writeString(dir.resolve("vision-route.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(route));
        return new PlatformModelConfig("explicit-isolated",1,"Explicit vision acceptance","LLM",true,
                "aliyun-bailian",service,profile,"openai-chat-completions","bearer-api-key","openai-compatible-chat-v1",
                endpoint,"qwen3-vl-plus",encrypted,128,8192,128,null,"DISABLED",60,null,null,null,null,null,"CNY",policy);
    }
    private static List<ModelMessage> messages(MediaRef image,boolean toolImage) {
        var instruction=new ModelMessage("instruction",ModelRole.SYSTEM,
                "Read the pixels. Return only JSON with shape (circle or square), color (red or blue), code (the six printed digits as a string).");
        if(!toolImage)return List.of(instruction,new ModelMessage("image",ModelRole.USER,List.of(new ModelContent.Image(image,ImageDetail.HIGH))));
        return List.of(instruction,new ModelMessage("user",ModelRole.USER,"Inspect the image returned by the image tool."),
                new ModelMessage("assistant",ModelRole.ASSISTANT,"",List.of(new ModelToolCall("image-call","read_image",
                        new TypedPayload("fixture.arguments","{}"))),Optional.empty(),Optional.empty()),
                new ModelMessage("tool-image",ModelRole.TOOL,List.of(new ModelContent.Text("Image tool result."),new ModelContent.Image(image,ImageDetail.HIGH)),
                        List.of(),Optional.of("image-call"),Optional.empty()));
    }
    private static String required(String name){var value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("MISSING_ENV_"+name);return value;}
    private static ModelCall call(SessionOwner owner,RequestedModelStrategy strategy,FrozenModelTarget target,MediaRef image,boolean toolImage) {
        String scene=(toolImage?"tool-":"user-")+UUID.randomUUID();
        var tenant=owner.sessionRef().tenantId();var input=new InputId("vision-input-"+scene);var now=Instant.now();
        var run=new RunIdentity(owner, owner.target().requireAgent(), input, new RunRef(tenant,"vision-run-"+scene), new TraceEnvelope("vision-trace-"+scene,"vision-span-"+scene,input,Optional.empty()), new ResultRef("vision-identity-"+scene), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(),"execution-input:"+(new RunRef(tenant,"vision-run-"+scene)).runId()));
        var action=new ActionRef(tenant,"vision-action-"+scene);var ref=new ResultRef("vision-invocation-"+scene);
        var dispatch=new ProviderDispatch(run.runRef(),action,"vision-invocation-"+scene,"vision-attempt-"+scene,PortalCallKind.MODEL_GENERATE,
                ref,new ResultRef("vision-capability-"+scene),"vision-fingerprint-"+scene,1);
        var identity=new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant,owner.requireUser()),owner,Set.of("runtime:invoke"),1,new ResultRef("vision-verified"));
        return new ModelCall(ProviderCallContext.dispatched(new PortCallContext(run,identity,new InvocationControl(now.plus(target.timeout()),()->false)),dispatch),strategy,target,ref,
                messages(image,toolImage),
                new ResponseContract(new ResponseFormat.Text(),EnforcementLevel.STRICT,"vision-fixture"),new GenerationOptions(OptionalInt.of(128),OptionalDouble.empty()),ReasoningPolicy.DISABLED);
    }
    private static final class Pixels implements MediaPort {
        final SessionRef owner;final MediaRef source;final Map<String,byte[]> bytes=new HashMap<>();
        Pixels(SessionRef owner,SessionMediaImages.Encoded encoded){this.owner=owner;source=ref("source",encoded);bytes.put(source.id(),encoded.bytes());}
        private MediaRef ref(String id,SessionMediaImages.Encoded encoded){return new MediaRef(owner,id,SessionMediaImages.digest(encoded.bytes()),encoded.mime(),encoded.bytes().length,encoded.width(),encoded.height());}
        public MediaRef prepare(SessionRef owner,MediaRef ref,ModelImagePolicy policy){
            if(!this.owner.equals(owner)||!source.equals(ref))throw new IllegalStateException("MEDIA_FIXTURE_OWNER_REJECTED");
            var encoded=SessionMediaImages.variant(SessionMediaImages.decode(bytes.get(ref.id())).pixels(),policy);
            var prepared=ref("prepared",encoded);bytes.put(prepared.id(),encoded.bytes());return prepared;
        }
        public byte[] read(SessionRef owner,MediaRef ref){if(!this.owner.equals(owner))throw new IllegalStateException("MEDIA_FIXTURE_OWNER_REJECTED");return bytes.get(ref.id()).clone();}
    }
}
