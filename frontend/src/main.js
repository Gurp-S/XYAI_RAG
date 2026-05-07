import { createApp } from "vue";
import { createPinia } from "pinia";
import App from "./App.vue";
import router from "./router";
import { useUiStore } from "./store";
import { installAuthFetch } from "./services/api";
import "./styles.css";
import "katex/dist/katex.css";

const app = createApp(App);
const pinia = createPinia();

app.use(pinia);
app.use(router);

const ui = useUiStore(pinia);
installAuthFetch();

// Dynamically load highlight.js theme based on current mode
function applyHighlightTheme(isDark) {
  const id = "hljs-theme-dynamic";
  let link = document.getElementById(id);
  if (!link) {
    link = document.createElement("link");
    link.id = id;
    link.rel = "stylesheet";
    document.head.appendChild(link);
  }
  link.href = isDark
    ? "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css"
    : "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github.min.css";
}

// Watch theme changes
const darkQuery = window.matchMedia("(prefers-color-scheme: dark)");
applyHighlightTheme(ui.darkMode ?? darkQuery.matches);

// Re-apply on store-driven dark mode change
let lastDark = ui.darkMode;
setInterval(() => {
  if (ui.darkMode !== lastDark) {
    lastDark = ui.darkMode;
    applyHighlightTheme(lastDark);
  }
}, 600);

async function bootstrap() {
  ui.initAuthSession();
  ui.initAppearance();
  app.mount("#app");
  await ui.bootstrapAuth();
}

bootstrap();
