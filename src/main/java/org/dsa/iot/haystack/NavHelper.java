package org.dsa.iot.haystack;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeBuilder;
import org.dsa.iot.dslink.node.NodeListener;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.util.Objects;
import org.projecthaystack.*;
import org.projecthaystack.client.CallErrException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.Handler;

import java.util.Iterator;
import java.util.Map;

/**
 * @author Samuel Grenier
 */
public class NavHelper {

    private static final Logger LOGGER;
    private final Haystack haystack;

    NavHelper(Haystack haystack) {
        this.haystack = haystack;
    }

    Handler<Node> getNavHandler(final String navId) {
        return new Handler<Node>() {
            @Override
            public void handle(final Node event) {
                Objects.getDaemonThreadPool().execute(new Runnable() {
                    @Override
                    public void run() {
                        HGrid grid = HGrid.EMPTY;
                        if (navId != null) {
                            HGridBuilder builder = new HGridBuilder();
                            builder.addCol("navId");
                            builder.addRow(new HVal[]{
                                    HUri.make(navId)
                            });
                            grid = builder.toGrid();
                            String path = event.getPath();
                            LOGGER.info("Navigating: {} ({})", navId, path);
                        } else {
                            LOGGER.info("Navigating root");
                        }

                        try {
                            HGrid nav = haystack.call("nav", grid);
                            iterateNavChildren(nav, event);
                        } catch (CallErrException ignored) {
                        }
                    }
                });
            }
        };
    }

    private void iterateNavChildren(HGrid nav, Node node) {
        Iterator navIt = nav.iterator();
        while (navIt != null && navIt.hasNext()) {
            final HRow row = (HRow) navIt.next();

            String name = getName(row);
            if (name == null) {
                continue;
            }

            final NodeBuilder builder = node.createChild(name);
            final Node child = builder.build();

            HVal navId = row.get("navId", false);
            if (navId != null) {
                String id = navId.toString();
                Handler<Node> handler = getNavHandler(id);
                child.getListener().addOnListHandler(handler);

                HGridBuilder hGridBuilder = new HGridBuilder();
                hGridBuilder.addCol("navId");
                hGridBuilder.addRow(new HVal[]{navId});
                HGrid grid = hGridBuilder.toGrid();
                HGrid children = haystack.call("nav", grid);
                Iterator childrenIt = children.iterator();
                while (childrenIt.hasNext()) {
                    final HRow childRow = (HRow) childrenIt.next();
                    final String childName = getName(childRow);
                    if (childName != null) {
                        Node n = child.createChild(childName).build();
                        navId = childRow.get("navId", false);
                        if (navId != null) {
                            id = navId.toString();
                            handler = getNavHandler(id);
                            n.getListener().addOnListHandler(handler);
                        }
                        iterateRow(n, childRow);
                    }
                }
            }

            iterateRow(child, row);
        }
    }

    private void iterateRow(Node node, HRow row) {
        handleRowValSubs(node, row);
        Iterator it = row.iterator();
        while (it.hasNext()) {
            Map.Entry entry = (Map.Entry) it.next();
            String name = (String) entry.getKey();
            if ("id".equals(name)) {
                continue;
            }
            HVal val = (HVal) entry.getValue();
            Value value = Utils.hvalToVal(val);

            Node child = node.createChild(name).build();
            child.setValue(value);
        }
    }

    private void handleRowValSubs(final Node node, HRow row) {
        final HVal id = row.get("id", false);
        if (id != null) {
            Node child = node.createChild("id").build();
            child.setValue(Utils.hvalToVal(id));
            NodeListener listener = child.getListener();
            listener.addOnSubscribeHandler(new Handler<Node>() {
                @Override
                public void handle(Node event) {
                    haystack.subscribe((HRef) id, node);
                }
            });

            listener.addOnUnsubscribeHandler(new Handler<Node>() {
                @Override
                public void handle(Node event) {
                    haystack.unsubscribe((HRef) id);
                }
            });
        }
    }

    private String getName(HRow row) {
        String name = Utils.filterBannedChars(row.dis());
        if (name.isEmpty() || "????".equals(name)) {
            return null;
        }
        return name;
    }

    static {
        LOGGER = LoggerFactory.getLogger(NavHelper.class);
    }
}
