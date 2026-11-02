<template>
  <main class="login-screen">
    <ElResult icon="warning" title="页面不存在" sub-title="请检查地址，或返回机构工作台。"
      ><template #extra
        ><RouterLink to="/admin/home"
          ><ElButton type="primary">返回工作台</ElButton></RouterLink
        ></template
      ></ElResult
    >
  </main>
</template>
