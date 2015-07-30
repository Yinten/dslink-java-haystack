package org.dsa.iot.haystack;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeBuilder;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.util.Objects;
import org.dsa.iot.dslink.util.StringUtils;
import org.dsa.iot.haystack.actions.ServerActions;
import org.dsa.iot.haystack.helpers.ConnectionHelper;
import org.dsa.iot.haystack.helpers.NavHelper;
import org.projecthaystack.*;
import org.projecthaystack.client.HClient;
import org.vertx.java.core.Handler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author Samuel Grenier
 */
public class Haystack {

    private final Map<String, Node> subs;
    private final NavHelper helper;
    private final Node node;

    private final ScheduledThreadPoolExecutor stpe;
    private ScheduledFuture<?> pollFuture;
    private ConnectionHelper conn;

    private HWatch watch;

    public Haystack(final Node node) {
        node.setRoConfig("lu", new Value(0));
        node.setMetaData(this);
        if (node.getConfig("pr") == null) {
            node.setConfig("pr", new Value(5));
        }
        this.stpe = Objects.createDaemonThreadPool();
        this.node = node;
        this.subs = new ConcurrentHashMap<>();
        this.helper = new NavHelper(this);
        this.conn = new ConnectionHelper(node, new Handler<HWatch>() {
            @Override
            public void handle(HWatch event) {
                watch = event;

                if (!subs.isEmpty()) {
                    // Restore haystack subscriptions
                    for (Map.Entry<String, Node> entry : subs.entrySet()) {
                        HRef id = HRef.make(entry.getKey());
                        Node node = entry.getValue();
                        subscribe(id, node);
                    }
                }

                setupPoll(node.getConfig("pr").getNumber().intValue());
            }
        }, new Handler<Void>() {
            @Override
            public void handle(Void event) {
                pollFuture.cancel(false);
                pollFuture = null;
                watch = null;
            }
        });
        // Ensure subscriptions are subscribed
        conn.getClient(null);
    }

    public void editConnection(String url,
                               String user,
                               String pass,
                               int pollRate) {
        conn.editConnection(url, user, pass);
        setupPoll(pollRate);

        Action a = ServerActions.getEditAction(node);
        node.getChild("editServer").setAction(a);
    }

    public void call(final String op,
               final HGrid grid,
               final Handler<HGrid> onComplete) {
        conn.getClient(new Handler<HClient>() {
            @Override
            public void handle(HClient event) {
                HGrid ret = event.call(op, grid);
                if (onComplete != null) {
                    onComplete.handle(ret);
                }
            }
        });
    }

    public void read(final String filter,
              final int limit,
              final Handler<HGrid> onComplete) {
        conn.getClient(new Handler<HClient>() {
            @Override
            public void handle(HClient event) {
                HGrid ret = event.readAll(filter, limit);
                if (onComplete != null) {
                    onComplete.handle(ret);
                }
            }
        });
    }

    public void eval(final String expr, final Handler<HGrid> onComplete) {
        conn.getClient(new Handler<HClient>() {
            @Override
            public void handle(HClient event) {
                HGrid ret = event.eval(expr);
                if (onComplete != null) {
                    onComplete.handle(ret);
                }
            }
        });
    }

    public ScheduledThreadPoolExecutor getStpe() {
        return stpe;
    }

    public ConnectionHelper getConnHelper() {
        return conn;
    }

    public NavHelper getNavHelper() {
        return helper;
    }

    public void subscribe(HRef id, Node node) {
        subscribe(id, node, true);
    }

    private void subscribe(final HRef id, Node node, boolean add) {
        if (watch == null) {
            return;
        }
        if (add) {
            subs.put(id.toString(), node);
        }

        conn.getClient(new Handler<HClient>() {
            @Override
            public void handle(HClient event) {
                HWatch watch = Haystack.this.watch;
                if (watch != null) {
                    watch.sub(new HRef[]{id});
                }
            }
        });
    }

    public void unsubscribe(final HRef id) {
        if (watch == null) {
            return;
        }
        subs.remove(id.toString());
        conn.getClient(new Handler<HClient>() {
            @Override
            public void handle(HClient event) {
                HWatch watch = Haystack.this.watch;
                if (watch != null) {
                    watch.unsub(new HRef[]{id});
                }
            }
        });
    }

    public void stop() {
        if (pollFuture != null) {
            try {
                pollFuture.cancel(true);
            } catch (Exception ignored) {
            }
        }

        watch = null;
        conn.close();
    }

    void destroy() {
        stop();
        stpe.shutdownNow();
        helper.destroy();
    }

    private void setupPoll(int time) {
        if (pollFuture != null) {
            pollFuture.cancel(false);
            pollFuture = null;
        }

        pollFuture = stpe.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    poll();
                } catch (Exception e) {
                    pollFuture.cancel(false);
                    pollFuture = null;
                    watch = null;
                }
            }
        }, time, time, TimeUnit.SECONDS);
    }

    private void poll() {
        HWatch watch = this.watch;
        if (watch == null || subs.isEmpty()) {
            return;
        }

        HGrid grid = watch.pollChanges();
        if (grid == null) {
            return;
        }

        Iterator it = grid.iterator();
        while (it.hasNext()) {
            HRow row = (HRow) it.next();
            Node node = subs.get(row.id().toString());
            if (node != null) {
                Map<String, Node> children = node.getChildren();
                List<String> remove = null;
                if (children != null) {
                    remove = new ArrayList<>(children.keySet());
                }

                Iterator rowIt = row.iterator();
                while (rowIt.hasNext()) {
                    Map.Entry entry = (Map.Entry) rowIt.next();
                    String name = (String) entry.getKey();
                    HVal val = (HVal) entry.getValue();
                    Value value = Utils.hvalToVal(val);

                    String encoded = StringUtils.encodeName(name);
                    Node child = null;
                    if (children != null) {
                        child = children.get(encoded);
                    }
                    if (child != null) {
                        child.setValueType(value.getType());
                        child.setValue(value);
                    } else {
                        NodeBuilder b = node.createChild(encoded);
                        b.setValueType(value.getType());
                        b.setValue(value);
                        Node n = b.build();
                        n.setSerializable(false);
                    }
                    if (remove != null) {
                        remove.remove(encoded);
                    }
                }

                if (remove != null) {
                    for (String s : remove) {
                        node.removeChild(s);
                    }
                }
            }
        }
    }

    public static void init(Node superRoot) {
        NodeBuilder builder = superRoot.createChild("addServer");
        builder.setDisplayName("Add Server");
        builder.setSerializable(false);
        builder.setAction(ServerActions.getAddServerAction(superRoot)).build();

        Map<String, Node> children = superRoot.getChildren();
        if (children != null) {
            for (Node child : children.values()) {
                if (child.getAction() == null) {
                    Haystack haystack = new Haystack(child);
                    child.clearChildren();
                    Utils.initCommon(haystack, child);
                }
            }
        }
    }
}
