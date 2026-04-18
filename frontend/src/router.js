import { createRouter, createWebHistory } from "vue-router";

const MainChat = () => import("./components/MainChat.vue");
const MilvusManager = () => import("./components/MilvusManager.vue");

const routes = [
  { path: "/", name: "chat", component: MainChat, alias: "/chat" },
  { path: "/db", name: "db", component: MilvusManager },
  { path: "/upload", redirect: "/" },
];

export default createRouter({ history: createWebHistory(), routes });
