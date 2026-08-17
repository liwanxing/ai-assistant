<script setup lang="ts">
import { onLaunch, onShow, onHide } from "@dcloudio/uni-app";
onLaunch(() => {
  console.log("App Launch");
});
onShow(() => {
  console.log("App Show");
});
onHide(() => {
  console.log("App Hide");
});
</script>
<style></style>


<!--UniApp 和普通 Vue 项目最大的区别之一：没有 vue-router，
                      路由是框架内置的，由 pages.json 声明
-->