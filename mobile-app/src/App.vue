<script setup lang="ts">
import { onLaunch } from "@dcloudio/uni-app";
import { getToken } from "./api/request";

/**
 * 小程序启动时检查登录态
 *
 * 流程：
 *   1. 读取本地 Token（uni.getStorageSync，对标 localStorage.getItem）
 *   2. 有 Token → 正常使用（TabBar 默认显示对话页）
 *   3. 没 Token → 跳转登录页
 *
 * 这和 PC 端的路由守卫（router.beforeEach）是同一个思路：
 *   PC 端：路由跳转前检查 token，没有就重定向到 /login
 *   小程序：启动时一次性检查，没有就跳到登录页
 *
 * 为什么不用路由守卫？
 *   Uni-app 没有 vue-router，路由由 pages.json 声明
 *   不能拦截 TabBar 页面的跳转，所以用启动检查 + 手动跳转代替
 */
onLaunch(() => {
  const token = getToken();
  if (!token) {
    // 没有 Token → 跳转登录页
    // reLaunch 会关闭所有页面再打开，防止用户点返回回到无 Token 的页面
    uni.reLaunch({ url: "/pages/login/login" });
  }
});
</script>

<style>
/* 全局样式：所有页面共享 */
page {
  background-color: #f5f5f5;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}
</style>
