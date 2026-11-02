package com.skillforge.institution.group;
import static org.junit.jupiter.api.Assertions.*;
import static com.skillforge.institution.group.GroupMessageContracts.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class GroupMessageContractsTest {
 private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
 @Test void authorUnionKeepsExactDecimalIdentityAndOrderedContent() throws Exception {
  var value=json.readValue("{\"group_binding_id\":\"41\",\"source_event_id\":\"source\",\"source_revision\":1,\"author\":{\"kind\":\"end_user\",\"end_user_id\":\"9007199254740993\"},\"content\":[{\"type\":\"text\",\"text\":\"first\"},{\"type\":\"text\",\"text\":\"second\"}]}",MessageRecord.class);
  assertEquals(9007199254740993L,((EndUserAuthor)value.author()).endUserId());
  var tree=json.readTree(json.writeValueAsString(value));assertEquals("end_user",tree.at("/author/kind").asText());
  assertEquals("9007199254740993",tree.at("/author/end_user_id").asText());assertEquals("second",tree.at("/content/1/text").asText());
 }
 @Test void allAuthorBranchesRoundTripWithoutInventedFields() throws Exception {
  for(Author author:List.of(new EndUserAuthor(31L),new AgentAuthor(51L,"run:1","message:1"),new ToolAuthor(51L,"run:1","action:1"),new SystemAuthor("event:1"))) {
   var body=json.writerFor(Author.class).writeValueAsString(author);assertEquals(author,json.readValue(body,Author.class));
  }
 }
 @Test void numericIdentityAndUnknownAuthorCannotSlipAcrossTheUnion() {
  assertThrows(JsonProcessingException.class,()->json.readValue("{\"kind\":\"end_user\",\"end_user_id\":9007199254740993}",Author.class));
  assertThrows(JsonProcessingException.class,()->json.readValue("{\"kind\":\"external\",\"source_ref\":\"opaque\"}",Author.class));
 }
 @Test void messageReceiptContainsExactlySixSourceFieldsAndNoExecution() throws Exception {
  var tree=json.readTree(json.writeValueAsString(new MessageReceipt("41","session","source",1,"event",1)));
  assertEquals(6,tree.size());assertFalse(tree.has("input_id"));assertFalse(tree.has("run_id"));assertEquals(1,tree.path("source_sequence").asLong());
 }
}
