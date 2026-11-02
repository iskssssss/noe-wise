package com.skillforge.institution.group;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.skillforge.common.context.*;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.institution.runtime.AgentRuntimeExecutionGateway;
import java.util.Set;
import org.junit.jupiter.api.*;

/** 定向命令只交给原Run控制面，条件非法请求不得进入Runtime。 */
class GroupMessageControllerTest {
 private final GroupMessageService messages=mock(GroupMessageService.class);
 private final GroupBindingService groups=mock(GroupBindingService.class);
 private final InstitutionIntegrationService integrations=mock(InstitutionIntegrationService.class);
 private final AgentRuntimeExecutionGateway runtime=mock(AgentRuntimeExecutionGateway.class);
 private final GroupMessageController controller=new GroupMessageController(messages,groups,integrations,runtime);
 @BeforeEach void setup(){TenantContext.setTenantId("11");ServicePrincipalContext.set(new ServicePrincipalContext.Identity("principal","11","integration",1,Set.of("groups.runs.control")));}
 @AfterEach void clear(){TenantContext.clear();ServicePrincipalContext.clear();}
 private GroupMessageContracts.RunCommand command(String action,String text){return new GroupMessageContracts.RunCommand("41","run:original","input:control",31L,action,text);}
 @Test void steerRequiresNonblankText(){assertEquals(400,assertThrows(ApiException.class,()->controller.command(command("steer"," "),"input:control")).getStatus().value());verifyNoInteractions(groups,runtime,messages);}
 @Test void cancelCannotSmuggleSteerText(){assertEquals(400,assertThrows(ApiException.class,()->controller.command(command("cancel_execution","change"),"input:control")).getStatus().value());verifyNoInteractions(groups,runtime,messages);}
 @Test void commandKeyMustBeTheStableInput(){assertEquals(400,assertThrows(ApiException.class,()->controller.command(command("cancel_execution",null),"another-input")).getStatus().value());verifyNoInteractions(groups,runtime,messages);}
 @Test void validCommandChecksCurrentMemberAndTargetsOriginalRunWithoutQueue(){
  when(groups.requireActive("11","integration","41")).thenReturn(new GroupContracts.GroupView("41","session","space","external","life","51","active",1,new GroupContracts.Subject("group","41")));
  when(runtime.groupCommand("11","integration","41","session","run:original","input:control",31,"steer","use original context")).thenReturn(new AgentRuntimeExecutionGateway.GroupRunResponse("41","session","run:original","input:control","waiting",false));
  controller.command(command("steer","use original context"),"input:control");
  verify(integrations).requirePrincipal(ServicePrincipalContext.get(),"groups.runs.control");
  verify(groups).requireMember("11","integration","41",31,false);
  verify(runtime).groupCommand("11","integration","41","session","run:original","input:control",31,"steer","use original context");
  verifyNoInteractions(messages);
 }
}
