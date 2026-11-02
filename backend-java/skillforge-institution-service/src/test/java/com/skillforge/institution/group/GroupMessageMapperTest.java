package com.skillforge.institution.group;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

/** Runtime自有Configuration必须能获取同一正式Mapper和guard全部SQL。 */
class GroupMessageMapperTest {
 @Test void configuresRuntimeOwnedSessionAndCanBeCalledAgain() {
  var configuration=new Configuration();configuration.setMapUnderscoreToCamelCase(true);
  GroupMessageMapper.configure(configuration);
  assertTrue(configuration.hasMapper(GroupMessageMapper.class));
  for(String method:new String[]{"lockSlot","head","eligible","accept","occupy","terminal","history"})
   assertTrue(configuration.hasStatement(GroupMessageMapper.class.getName()+"."+method),method);
  int statements=configuration.getMappedStatementNames().size();GroupMessageMapper.configure(configuration);
  assertEquals(statements,configuration.getMappedStatementNames().size());
  assertNotNull(configuration.getMapper(GroupMessageMapper.class,mock(SqlSession.class)));
 }
}
