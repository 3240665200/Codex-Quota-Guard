import i18n from "i18next";
import { initReactI18next } from "react-i18next";

void i18n.use(initReactI18next).init({
  lng: "zh-CN",
  fallbackLng: "zh-CN",
  interpolation: { escapeValue: false },
  resources: {
    "zh-CN": {
      translation: {
        appName: "Codex Quota Guard",
        dashboard: "额度仪表盘",
        overview: "查看后台分配账户、额度策略和个人真实用量。",
        refresh: "刷新数据",
        refreshing: "刷新中…",
        connected: "本地服务在线",
        serviceUnavailable: "本地服务不可用",
        retry: "重试",
        dataRefreshed: "数据已刷新",
        refreshFailed: "刷新失败",
      },
    },
  },
});

export default i18n;
