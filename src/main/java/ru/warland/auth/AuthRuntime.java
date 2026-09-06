package ru.warland.auth;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.*;
import net.minecraft.text.Text;
import ru.warland.core.CoreRuntime;
import ru.warland.mixin.AuthCommonAccess;
import ru.warland.mixin.AuthSourceAccess;
import static net.minecraft.server.command.CommandManager.literal;

/** Fail-closed CONFIGURATION barrier: no pre-auth player entity exists in a world. */
public final class AuthRuntime implements AutoCloseable {
    public static final ServerPlayerConfigurationTask.Key TASK = new ServerPlayerConfigurationTask.Key("warland:authentication");
    private final CoreRuntime runtime;
    private final AuthRepository repository;
    private final Authentication engine;
    private final Map<ClientConnection, Session> sessions = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private final AtomicBoolean provisioning = new AtomicBoolean();
    private static final Text DENIED = Text.literal("WarLand: безопасная авторизация недоступна. Переподключитесь с клиентом WarLand.");
    private static final class Session {
        final ServerConfigurationNetworkHandler configuration;
        final Authentication.Connection identity;
        final long opened = System.nanoTime();
        final AtomicBoolean busy = new AtomicBoolean();
        volatile boolean sent, released, owner, profileReady;
        volatile ServerPlayNetworkHandler play;
        Session(ServerConfigurationNetworkHandler h, Authentication.Connection identity) { this.configuration = h; this.identity = identity; }
    }
    public AuthRuntime(CoreRuntime runtime) {
        this.runtime = runtime; repository = new AuthRepository(runtime.store); engine = new Authentication(repository);
    }
    public CompletableFuture<Void> start() { return repository.start(); }
    public void initialize() {
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !(entity instanceof ServerPlayerEntity p) || runtime.authorized(p));
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> !(entity instanceof ServerPlayerEntity p) || runtime.authorized(p));
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> player instanceof ServerPlayerEntity p && !runtime.authorized(p) ? net.minecraft.util.ActionResult.FAIL : net.minecraft.util.ActionResult.PASS);
        ConfigurationOrder.beforeDefaults(ServerConfigurationConnectionEvents.BEFORE_CONFIGURE, this::configure);
        ServerConfigurationConnectionEvents.DISCONNECT.register((h, s) -> disconnect(h));
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> disconnect(h));
        ServerConfigurationNetworking.registerGlobalReceiver(AuthPayloads.Request.ID, (p, context) -> receive(context.networkHandler(), p));
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            for (var entry : sessions.entrySet()) {
                Session s = entry.getValue();
                if (!entry.getKey().isOpen() || (s.released ? !engine.authenticated(s.identity) : System.nanoTime() - s.opened >= TimeUnit.SECONDS.toNanos(120))) {
                    remove(entry.getKey(), s); entry.getKey().disconnect(DENIED);
                }
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
            literal("warland-owner-bootstrap").requires(this::console).executes(context -> provision(context.getSource()))));
    }
    public static ClientConnection connection(ServerCommonNetworkHandler handler) {
        return ((AuthCommonAccess) handler).warland$connection();
    }
    private synchronized void configure(ServerConfigurationNetworkHandler handler, net.minecraft.server.MinecraftServer server) {
        ClientConnection connection = connection(handler);
        if (closed || !runtime.ready() || !RuntimePolicy.secure(server.isOnlineMode(), connection.isEncrypted())
                || ServerConfigurationNetworking.isReconfiguring(handler) || !ServerConfigurationNetworking.canSend(handler, AuthPayloads.Challenge.ID)
                || sessions.containsKey(connection) || sessions.size() >= 128
                || !(connection.getAddress() instanceof InetSocketAddress address) || address.getAddress() == null) {
            handler.disconnect(DENIED); return;
        }
        try {
            var profile = ((AuthCommonAccess) handler).warland$profile();
            Session session = new Session(handler, engine.reserve(profile.id(), profile.name(), address.getAddress().getHostAddress()));
            sessions.put(connection, session);
            handler.addTask(new ServerPlayerConfigurationTask() {
                public Key getKey() { return TASK; }
                public void sendPacket(Consumer<Packet<?>> sender) {
                    if (!current(connection, session)) { handler.disconnect(DENIED); return; }
                    session.sent = true;
                    sender.accept(ServerConfigurationNetworking.createS2CPacket(new AuthPayloads.Challenge(session.identity.nonce(), 0)));
                }
            });
        } catch (RuntimeException unavailable) { disconnect(handler); handler.disconnect(DENIED); }
    }
    private void receive(ServerConfigurationNetworkHandler handler, AuthPayloads.Request request) {
        ClientConnection connection = connection(handler); Session s = sessions.get(connection);
        try (request) {
            if (s == null || s.configuration != handler || !current(connection, s) || !s.sent || s.released
                    || !connection.isEncrypted() || !s.identity.nonce().equals(request.nonce) || !request.valid()) {
                handler.disconnect(DENIED); return;
            }
            if (!s.busy.compareAndSet(false, true)) return;
            CompletableFuture<Authentication.Result> result = request.registration
                    ? engine.register(s.identity, request.password(), request.ownerToken(), System.currentTimeMillis())
                    : engine.login(s.identity, request.password());
            result.thenCompose(value -> value == Authentication.Result.SUCCESS
                    ? engine.owner(s.identity).thenApply(owner -> new Outcome(true, owner))
                    : CompletableFuture.completedFuture(new Outcome(false, false)))
                .whenComplete((outcome, error) -> ServerConfigurationNetworking.getServer(handler).execute(() -> {
                    s.busy.set(false);
                    if (!current(connection, s) || s.released) return;
                    if (error != null) { remove(connection, s); handler.disconnect(DENIED); return; }
                    if (!outcome.success()) { ServerConfigurationNetworking.send(handler, new AuthPayloads.Challenge(s.identity.nonce(), 1)); return; }
                    if (!engine.authenticated(s.identity)) { remove(connection, s); handler.disconnect(DENIED); return; }
                    s.owner = outcome.owner(); s.released = true;
                    ServerConfigurationNetworking.send(handler, new AuthPayloads.Challenge(s.identity.nonce(), 2));
                    handler.completeTask(TASK);
                }));
        } catch (RuntimeException invalid) { if (s != null) remove(connection, s); handler.disconnect(DENIED); }
    }
    private record Outcome(boolean success, boolean owner) {}
    private boolean current(ClientConnection c, Session s) {
        return !closed && runtime.ready() && c.isOpen() && sessions.get(c) == s
                && (s.released ? engine.authenticated(s.identity) : System.nanoTime() - s.opened < TimeUnit.SECONDS.toNanos(120));
    }
    public boolean attach(ServerPlayerEntity player) {
        Session s = sessions.get(connection(player.networkHandler));
        if (s == null || !s.released || !s.identity.player().equals(player.getUuid()) || !engine.authenticated(s.identity)) return false;
        if (s.play != null && s.play != player.networkHandler) return false;
        s.play = player.networkHandler; return true;
    }
    public boolean authenticated(ServerPlayerEntity player) {
        if (player == null || player.networkHandler == null) return false;
        ClientConnection c = connection(player.networkHandler); Session s = sessions.get(c);
        return s != null && s.released && current(c, s) && s.play == player.networkHandler
                && player.networkHandler.player == player && s.identity.player().equals(player.getUuid()) && engine.authenticated(s.identity);
    }
    public boolean allowed(ServerPlayerEntity player) {
        if (!authenticated(player)) return false;
        Session s = sessions.get(connection(player.networkHandler)); return s != null && s.profileReady;
    }
    /** Capture on the server thread. Evaluation never resolves a replacement player by UUID. */
    public BooleanSupplier lease(ServerPlayerEntity player) {
        if (!allowed(player)) return () -> false;
        ServerPlayNetworkHandler play = player.networkHandler;
        ClientConnection c = connection(play); Session s = sessions.get(c);
        if (s == null || s.play != play || !s.released || !s.profileReady
                || !s.identity.player().equals(player.getUuid())) return () -> false;
        return () -> !closed && runtime.ready() && c.isOpen() && sessions.get(c) == s
                && s.play == play && s.released && s.profileReady && engine.authenticated(s.identity);
    }
    public void profileReady(ServerPlayerEntity player) {
        if (authenticated(player)) { Session s = sessions.get(connection(player.networkHandler)); if(s != null) s.profileReady = true; }
    }
    public boolean owner(ServerPlayerEntity player) {
        if (!allowed(player)) return false;
        Session s = sessions.get(connection(player.networkHandler)); return s != null && s.owner;
    }
    public boolean configurationReleased(ServerConfigurationNetworkHandler handler) {
        ClientConnection c = connection(handler); Session s = sessions.get(c);
        return s != null && s.configuration == handler && s.released && current(c, s);
    }
    public void disconnect(ServerCommonNetworkHandler handler) {
        ClientConnection c = connection(handler); Session s = sessions.get(c);
        if (s != null && (s.play == handler || s.configuration == handler && s.play == null)) remove(c, s);
    }
    private void remove(ClientConnection c, Session s) { if (sessions.remove(c, s)) engine.disconnect(s.identity); }
    public boolean console(ServerCommandSource source) {
        AuthSourceAccess access = (AuthSourceAccess) source;
        boolean ownerPermissions = source.getPermissions().hasPermission(new net.minecraft.command.permission.Permission.Level(net.minecraft.command.permission.PermissionLevel.OWNERS));
        return !closed && runtime.ready() && RuntimePolicy.provisioningContext(access.warland$silent(), ownerPermissions)
                && RuntimePolicy.console(access.warland$output(), runtime.server(), source.getEntity() != null);
    }
    private int provision(ServerCommandSource source) {
        if (!console(source) || !provisioning.compareAndSet(false, true)) return 0;
        final Path target;
        try {
            Path dir = runtime.dataDirectory().resolve("private-auth");
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            if (Files.isSymbolicLink(dir) || !Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------"))) throw new IllegalStateException();
            target = dir.resolve("owner-bootstrap.txt");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException();
        } catch (Exception unavailable) {
            provisioning.set(false); source.sendError(Text.literal("Нужен приватный POSIX-каталог; существующий bootstrap не перезаписывается.")); return 0;
        }
        long now = System.currentTimeMillis();
        engine.provisionOwner(now, now + TimeUnit.MINUTES.toMillis(15)).thenAccept(token -> {
            byte[] bytes = token.getBytes(StandardCharsets.US_ASCII);
            try (FileChannel file = FileChannel.open(target, Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                ByteBuffer b = ByteBuffer.wrap(bytes); while (b.hasRemaining()) file.write(b); file.force(true);
            } catch (Exception e) { throw new CompletionException(new IllegalStateException("Private bootstrap file unavailable")); }
            finally { Arrays.fill(bytes, (byte) 0); }
        }).whenComplete((v, error) -> runtime.server().execute(() -> {
            provisioning.set(false);
            if (closed) return;
            source.sendFeedback(() -> Text.literal(error == null ? "Bootstrap сохранён в private-auth/owner-bootstrap.txt (0600), срок 15 минут. Передайте приватно и удалите файл." : "Bootstrap не выдан. Проверьте приватный каталог; секрет не выводился."), false);
        }));
        return 1;
    }
    @Override public void close() {
        closed = true; engine.close();
        sessions.forEach((connection, session) -> { engine.disconnect(session.identity); connection.disconnect(DENIED); }); sessions.clear();
    }
}
