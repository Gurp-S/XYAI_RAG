import { createRouter, createWebHistory } from "vue-router";

const MainChat = () => import("./components/MainChat.vue");

const routes = [
  { path: "/", component: MainChat },
  { path: "/upload", redirect: "/" },
];

export default createRouter({ history: createWebHistory(), routes });
