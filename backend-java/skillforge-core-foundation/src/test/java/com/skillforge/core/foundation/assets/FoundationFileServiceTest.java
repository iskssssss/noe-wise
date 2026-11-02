package com.skillforge.core.foundation.assets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.beans.factory.ObjectProvider;

/** 真实临时目录验证落盘、摘要、失败回收与引用删除保护；数据库竞争由组合验收验证。 */
class FoundationFileServiceTest {
    @TempDir Path directory;
    private FoundationFileMapper mapper;
    private FoundationAccess access;
    private FoundationFileService files;
    private FoundationFileDO stored;
    /** 每例独立目录与事务替身，不修改全局环境或真实业务数据。 */
    @BeforeEach void setup(){mapper=mock(FoundationFileMapper.class);access=mock(FoundationAccess.class);when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true,null,"1"));var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());var properties=new FoundationFileProperties();properties.setRoot(directory.toString());properties.setMaxBytes(1024);files=new FoundationFileService(mapper,access,mock(FeatureGate.class),mock(ResourceQuota.class),manager,properties,mock(ObjectProvider.class));when(mapper.insert(any())).thenAnswer(i->{stored=i.getArgument(0);stored.setCreatedAt(OffsetDateTime.now());return 1;});when(mapper.lock(anyString())).thenAnswer(i->stored);}
    @Test void uploadAndDownloadPreserveBytesAndDigest(){byte[] bytes="name\r\n测试".getBytes(StandardCharsets.UTF_8);var result=files.upload("test.csv",new ByteArrayInputStream(bytes));assertArrayEquals(bytes,files.download(result.id()).bytes());assertEquals(FoundationFileService.digest(bytes),result.sha256());verify(mapper,atLeastOnce()).bind("",true);}
    @Test void generatedResultWithoutBusinessReferenceFailsClosed(){var file=files.createResult("result.csv","a,b".getBytes(StandardCharsets.UTF_8));when(mapper.references(file.id())).thenReturn(java.util.List.of());assertEquals(HttpStatus.FORBIDDEN,assertThrows(ApiException.class,()->files.download(file.id())).getStatus());}
    @Test void unauthorizedUploadDoesNotTouchDisk()throws Exception{doThrow(new ApiException(HttpStatus.FORBIDDEN,"禁止")).when(access).require("foundation.files.upload");assertThrows(ApiException.class,()->files.upload("test.csv",new ByteArrayInputStream(new byte[]{1})));try(var entries=Files.list(directory)){assertEquals(0,entries.count());}verifyNoInteractions(mapper);}
    @Test void capacityRejectsBeforePersistence()throws Exception{assertThrows(ApiException.class,()->files.upload("test.csv",new ByteArrayInputStream(new byte[1025])));verify(mapper,never()).insert(any());try(var entries=Files.list(directory)){assertEquals(0,entries.count());}}
    @Test void metadataFailureReclaimsCreatedBlob()throws Exception{doThrow(new IllegalStateException("db unavailable")).when(mapper).insert(any());assertThrows(IllegalStateException.class,()->files.upload("test.csv",new ByteArrayInputStream("a,b".getBytes(StandardCharsets.UTF_8))));try(var entries=Files.list(directory)){assertEquals(0,entries.count());}}
    @Test void activeReferenceBlocksPhysicalDeletion()throws Exception{var file=files.upload("test.csv",new ByteArrayInputStream("a,b".getBytes(StandardCharsets.UTF_8)));when(mapper.markDeleting(file.id())).thenReturn(0);var error=assertThrows(ApiException.class,()->files.delete(file.id()));assertEquals("file_referenced",error.getCode());assertTrue(Files.exists(directory.resolve(file.id()+".blob")));verify(mapper,never()).markDeleted(anyString());}
    @Test void tamperedContentIsNotDownloaded()throws Exception{var file=files.upload("test.csv",new ByteArrayInputStream("a,b".getBytes(StandardCharsets.UTF_8)));Files.writeString(directory.resolve(file.id()+".blob"),"changed");assertEquals("file_integrity_failed",assertThrows(ApiException.class,()->files.download(file.id())).getCode());}
}
