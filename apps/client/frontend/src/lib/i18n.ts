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
        overview: "查看团队共享额度、成员 Token 用量与官方额度百分比。",
        refresh: "刷新数据",
        refreshing: "刷新中…",
        connected: "中央服务器在线",
        serviceUnavailable: "中央服务器不可用",
        retry: "重试",
        dataRefreshed: "数据已刷新",
        refreshFailed: "刷新失败",
      },
    },
  },
});

export default i18n;
