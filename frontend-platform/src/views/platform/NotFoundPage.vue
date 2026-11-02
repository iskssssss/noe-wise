<template>
  <ElResult icon="warning" title="页面不存在" sub-title="该地址不存在，请从导航菜单进入。">
    <template #extra>
      <RouterLink to="/platform/home">
        <ElButton>返回首页</ElButton>
      </RouterLink>
      <RouterLink to="/login?switchAccount=1">
        <ElButton>切换账号</ElButton>
      </RouterLink>
    </template>
  </ElResult>
</template>
