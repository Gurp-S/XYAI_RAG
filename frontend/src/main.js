import { createApp } from "vue";
import { createPinia } from "pinia";
import App from "./App.vue";
import router from "./router";
import { useUiStore } from "./store";
import { installAuthFetch } from "./services/api";
import "./styles.css";
import "./styles_minimal.css";
import "highlight.js/styles/github.css";
import "katex/dist/katex.css";

const app = createApp(App);
const pinia = createPinia();

app.use(pinia);
app.use(router);

const ui = useUiStore(pinia);
installAuthFetch();

async function bootstrap() {
  ui.initAppearance();
  app.mount("#app");
  await ui.bootstrapAuth();
}

bootstrap();
