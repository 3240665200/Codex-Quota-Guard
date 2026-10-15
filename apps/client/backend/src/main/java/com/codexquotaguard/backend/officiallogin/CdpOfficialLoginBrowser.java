package com.codexquotaguard.backend.officiallogin;

import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class CdpOfficialLoginBrowser implements OfficialLoginBrowser {
    private static final String OFFICIAL_SESSIONS="https://chatgpt.com/settings/security?view=sessions";
    private final ObjectMapper mapper;
    CdpOfficialLoginBrowser(ObjectMapper mapper) { this.mapper=mapper; }

    @Override public Session open(Browser browser) {
        Path executable=installedBrowser(browser);
        PrivateBrowserProfile profile=PrivateBrowserProfile.create(Path.of(System.getProperty("java.io.tmpdir")));
        Process process=null;OwnedSession session=null;
        try {
            process=new ProcessBuilder(command(executable,profile.path())).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            session=new OwnedSession(profile,process,mapper);
            URI debugger=awaitDebugger(profile,process::isAlive,System::nanoTime,() -> {
                try { Thread.sleep(50); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw failure(); }
            });
            session.connect(debugger);return session;
        } catch(IOException|RuntimeException failure) { throw failure(); }
        finally {
            if(session==null || !session.ready.get()) {
                if(session!=null) session.close();
                else { if(process!=null) process.destroy();profile.close(); }
            }
        }
    }

    static URI awaitDebugger(PrivateBrowserProfile profile,BooleanSupplier alive,LongSupplier nanos,Runnable pause) {
        long start=nanos.getAsLong();
        while(alive.getAsBoolean() && nanos.getAsLong()-start<TimeUnit.SECONDS.toNanos(20)) {
            try { return profile.debuggerUri(); }
            catch(OfficialLoginException notReady) { pause.run(); }
        }
        throw failure();
    }

    static List<String> command(Path executable,Path profile) {
        return List.of(executable.toString(),"--user-data-dir="+profile.toAbsolutePath().normalize(),
                "--remote-debugging-port=0","--remote-debugging-address=127.0.0.1","--no-first-run","--no-default-browser-check","about:blank");
    }

    private static Path installedBrowser(Browser browser) {
        if(browser==null || !System.getProperty("os.name","").startsWith("Windows")) throw new OfficialLoginException("OFFICIAL_LOGIN_BROWSER_NOT_FOUND");
        String relative=browser==Browser.EDGE ? "Microsoft/Edge/Application/msedge.exe" : "Google/Chrome/Application/chrome.exe";
        for(String key:List.of("ProgramFiles","ProgramFiles(x86)","LOCALAPPDATA")) {
            String root=System.getenv(key);if(root==null || root.isBlank()) continue;
            Path expected=Path.of(root).toAbsolutePath().normalize().resolve(relative);
            try {
                if(Files.isRegularFile(expected,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(expected)
                        && expected.toRealPath().equals(expected)) return expected;
            } catch(IOException ignored) { }
        }
        throw new OfficialLoginException("OFFICIAL_LOGIN_BROWSER_NOT_FOUND");
    }

    private static final class OwnedSession implements Session {
        final PrivateBrowserProfile profile;
        final Process process;
        final BrowserCredentialCapture capture;
        final CompletableFuture<Authorization> result=new CompletableFuture<>();
        final AtomicBoolean closed=new AtomicBoolean(),ready=new AtomicBoolean();
        final CdpConnection cdp;
        volatile String sessionId;

        OwnedSession(PrivateBrowserProfile profile,Process process,ObjectMapper mapper) {
            this.profile=profile;this.process=process;this.capture=new BrowserCredentialCapture(mapper);
            HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10))
                    .proxy(new ProxySelector() {
                        @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                        @Override public void connectFailed(URI uri,SocketAddress address,IOException failure) { }
                    }).build();
            this.cdp=new CdpConnection(mapper,http,event -> {
                if(!closed.get() && sessionId!=null && sessionId.equals(mapper.readTree(event).path("sessionId").asString(""))) capture.accept(event);
            });
            cdp.termination().thenRun(() -> { if(!closed.get()) result.completeExceptionally(failure()); });
            process.onExit().thenRun(() -> { if(!closed.get()) result.completeExceptionally(failure()); });
        }

        void connect(URI debugger) {
            cdp.connect(debugger);
            JsonNode targets=call("Target.getTargets",Map.of(),null);
            String target=null;
            for(JsonNode info:targets.path("targetInfos")) {
                if("page".equals(info.path("type").asString("")) && "about:blank".equals(info.path("url").asString(""))) { target=info.path("targetId").asString();break; }
            }
            if(target==null) target=call("Target.createTarget",Map.of("url","about:blank"),null).path("targetId").asString("");
            sessionId=call("Target.attachToTarget",Map.of("targetId",target,"flatten",true),null).path("sessionId").asString("");
            if(sessionId.isBlank()) throw failure();
            call("Network.enable",Map.of(),sessionId);
            capture.result().thenAcceptAsync(credentials -> {
                if(closed.get()) return;
                try {
                    String cookie=BrowserCredentialCapture.loginCookies(call("Network.getCookies",Map.of("urls",List.of("https://chatgpt.com")),sessionId));
                    if(!closed.get()) result.complete(new Authorization(credentials.authorization(),cookie.isBlank()?null:cookie,credentials.requestedAccountId()));
                } catch(RuntimeException ignored) { result.completeExceptionally(failure()); }
            });
            call("Page.navigate",Map.of("url",OFFICIAL_SESSIONS),sessionId);
            ready.set(true);
        }

        private JsonNode call(String method,Map<String,?> params,String session) {
            try { return cdp.send(method,params,session).get(11,TimeUnit.SECONDS); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw failure(); }
            catch(Exception ignored) { throw failure(); }
        }
        @Override public CompletableFuture<Authorization> authorization() { return result; }
        @Override public void close() {
            if(!closed.compareAndSet(false,true)) return;
            boolean wasInterrupted=Thread.interrupted();
            try {
            capture.clear();result.obtrudeException(failure());
            try { cdp.send("Browser.close",Map.of(),null).get(2,TimeUnit.SECONDS); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch(Exception ignored) { }
            cdp.close();
            if(process.isAlive()) {
                process.destroy();
                try { if(!process.waitFor(2,TimeUnit.SECONDS)) process.destroyForcibly().waitFor(2,TimeUnit.SECONDS); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt();process.destroyForcibly(); }
            }
            profile.close();
            } finally { if(wasInterrupted) Thread.currentThread().interrupt(); }
        }
    }
    private static OfficialLoginException failure() { return new OfficialLoginException("OFFICIAL_LOGIN_BROWSER_UNAVAILABLE"); }
}
