<script setup>
// 不需要手动引入组件了，路由会自动渲染对应的页面
</script>

<template>
  <!-- router-view：路由出口，类似后端的 DispatcherServlet -->
  <!-- 根据当前 URL，自动渲染对应的页面组件 -->
  <!-- /login → LoginView.vue，/user → UserView.vue -->
  <router-view />
</template>
