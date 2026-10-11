import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { I18nextProvider } from "react-i18next";

import { Toaster } from "@/components/ui/sonner";
import { ClientApp } from "@/components/client-app";
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

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <ClientThemeProvider>
          <ClientApp />
          <Toaster />
        </ClientThemeProvider>
      </QueryClientProvider>
    </I18nextProvider>
  </StrictMode>,
);
