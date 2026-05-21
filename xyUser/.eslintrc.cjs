module.exports = {
  root: true,
  env: {
    browser: true,
    node: true,
    es2021: true,
  },
  extends: ["eslint:recommended", "plugin:vue/vue3-recommended", "prettier"],
  parserOptions: {
    ecmaVersion: 2020,
    sourceType: "module",
  },
  rules: {
    // 项目可根据需要覆盖规则，这里保留较宽松默认
    "no-console": "warn",
    "no-debugger": "warn",
    "vue/multi-word-component-names": "off",
  },
};
