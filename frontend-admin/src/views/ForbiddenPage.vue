<template>
  <main class="login-screen">
    <ElResult
      icon="warning"
      title="没有访问权限"
      sub-title="当前账号无权访问此页面。可返回工作台，或联系机构管理员确认所需权限。"
    >
      <template #extra>
        <div class="admin-actions">
          <RouterLink to="/admin/home"><ElButton type="primary">返回工作台</ElButton></RouterLink>
          <RouterLink to="/admin/foundation/security"><ElButton>账号安全</ElButton></RouterLink>
        </div>
      </template>
    </ElResult>
  </main>
</template>
