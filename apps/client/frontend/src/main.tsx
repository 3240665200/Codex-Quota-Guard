import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { I18nextProvider } from "react-i18next";

import { Toaster } from "@/components/ui/sonner";
import { ClientApp } from "@/components/client-app";
import { TrayPanel } from "@/components/tray-panel";
import { ClientThemeProvider } from "@/lib/client-theme";
import i18n from "@/lib/i18n";
import "@/index.css";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      refetchOnWindowFocus: false,
    },
  },
});

const isTrayPanel =
  new URLSearchParams(window.location.search).get("view") === "tray";

createRoot(document.getElementById("root")!).render(
  isTrayPanel ? (
    <StrictMode>
      <TrayPanel />
    </StrictMode>
  ) : (
    <StrictMode>
      <I18nextProvider i18n={i18n}>
        <QueryClientProvider client={queryClient}>
          <ClientThemeProvider>
            <ClientApp />
            <Toaster />
          </ClientThemeProvider>
        </QueryClientProvider>
      </I18nextProvider>
    </StrictMode>
  ),
);
