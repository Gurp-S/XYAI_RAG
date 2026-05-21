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
const HLJS_LIGHT =
  "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github.min.css";
const HLJS_DARK =
  "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css";

function preloadHighlightThemes() {
  const themes = [
    { id: "hljs-theme-light", href: HLJS_LIGHT, dark: false },
    { id: "hljs-theme-dark", href: HLJS_DARK, dark: true },
  ];
  for (const t of themes) {
    let link = document.getElementById(t.id);
    if (!link) {
      link = document.createElement("link");
      link.id = t.id;
      link.rel = "stylesheet";
      link.disabled = true;
      document.head.appendChild(link);
    }
    link.href = t.href;
  }
}

function applyHighlightTheme(isDark) {
  const lightLink = document.getElementById("hljs-theme-light");
  const darkLink = document.getElementById("hljs-theme-dark");
  if (lightLink) lightLink.disabled = isDark;
  if (darkLink) darkLink.disabled = !isDark;
}

// Watch theme changes
const darkQuery = window.matchMedia("(prefers-color-scheme: dark)");
preloadHighlightThemes();
applyHighlightTheme(ui.darkMode ?? darkQuery.matches);

// Subscribe to Pinia store changes for instant theme switch
ui.$subscribe(() => {
  applyHighlightTheme(ui.darkMode);
});

async function bootstrap() {
  ui.initAuthSession();
  ui.initAppearance();
  app.mount("#app");
  await ui.bootstrapAuth();
}

bootstrap();
