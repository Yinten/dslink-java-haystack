package org.dsa.iot.haystack.actions;

import org.dsa.iot.dslink.methods.StreamState;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.*;
import org.dsa.iot.dslink.node.actions.table.Row;
import org.dsa.iot.dslink.node.actions.table.Table;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.haystack.Haystack;
import org.dsa.iot.haystack.Utils;
import org.dsa.iot.haystack.helpers.SubHelper;
import org.projecthaystack.*;
import org.projecthaystack.client.HClient;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonObject;

import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author Samuel Grenier
 */
public class Actions {

    public static Action getSubscribeAction(final Haystack haystack) {
        Action a = new Action(Permission.READ, new Handler<ActionResult>() {

            @Override
            public void handle(ActionResult event) {
                Value vId = event.getParameter("ID", ValueType.STRING);
                String id = vId.getString();
                Value vPoll = event.getParameter("Poll Rate", ValueType.NUMBER);
                int pollRate = vPoll.getNumber().intValue();

                final SubHelper helper = new SubHelper(haystack, id);
                event.setCloseHandler(new Handler<Void>() {
                    @Override
                    public void handle(Void event) {
                        helper.stop();
                    }
                });
                event.setStreamState(StreamState.OPEN);
                helper.start(event.getTable(), pollRate);
            }
        });
        {
            Parameter p = new Parameter("ID", ValueType.STRING);
            p.setDescription("Haystack ref ID to subscribe to.");
            a.addParameter(p);
        }
        {
            Value def = new Value(5000);
            Parameter p = new Parameter("Poll Rate", ValueType.NUMBER, def);
            p.setDescription("Poll Rate is in milliseconds.");
            a.addParameter(p);
        }
        a.setResultType(ResultType.STREAM);
        return a;
    }

    public static Action getInvokeAction(final Haystack haystack) {
        Action a = new Action(Permission.READ, new Handler<ActionResult>() {
            @Override
            public void handle(final ActionResult event) {
                final CountDownLatch latch = new CountDownLatch(1);
                haystack.getConnHelper().getClient(new Handler<HClient>() {
                    @Override
                    public void handle(HClient client) {
                        Value vID = event.getParameter("ID", ValueType.STRING);
                        final HRef id = Utils.idToRef(vID);

                        Value vAct = event.getParameter("Action", ValueType.STRING);
                        final String act = vAct.getString();

                        Value vArgs = event.getParameter("Args", ValueType.MAP);
                        final JsonObject args = vArgs.getMap();

                        HDictBuilder b = new HDictBuilder();
                        {
                            String s = args.getString("str");
                            if (s != null) {
                                b.add("str", s);
                            }

                            Boolean bool = args.getBoolean("bool");
                            if (bool != null) {
                                b.add("bool", bool);
                            }

                            Number num = args.getNumber("number");
                            if (num != null) {
                                b.add("number", num.doubleValue());
                            }
                        }
                        HGrid res = client.invokeAction(id, act, b.toDict());
                        buildTable(res, event);
                        latch.countDown();
                    }
                });
                try {
                    if (!latch.await(5, TimeUnit.SECONDS)) {
                        String err = "Failed to retrieve data";
                        throw new RuntimeException(err);
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        {
            Parameter p = new Parameter("ID", ValueType.STRING);
            p.setDescription("Haystack ref ID to write to.");
            a.addParameter(p);
        }
        {
            Parameter p = new Parameter("Action", ValueType.STRING);
            p.setDescription("Name of the action to set");
            a.addParameter(p);
        }
        {
            Parameter p = new Parameter("Args", ValueType.MAP);
            JsonObject def = new JsonObject();
            def.putString("str", null);
            def.putBoolean("bool", null);
            def.putNumber("number", null);
            p.setDefaultValue(new Value(def));
            a.addParameter(p);
        }
        a.setResultType(ResultType.TABLE);
        return a;
    }

    public static Action getPointWriteAction(Haystack haystack) {
        return getPointWriteAction(haystack, null);
    }

    public static Action getPointWriteAction(final Haystack haystack,
                                             final HRef treeId) {
        Action a = new Action(Permission.READ, new Handler<ActionResult>() {
            @Override
            public void handle(final ActionResult event) {
                haystack.getConnHelper().getClient(new Handler<HClient>() {
                    @Override
                    public void handle(HClient client) {
                        Value vLev = event.getParameter("Level", ValueType.STRING);
                        Value vValue = event.getParameter("Value");
                        Value vVT = event.getParameter("Value Type");
                        Value vUnit = event.getParameter("Value Unit");
                        Value vWho = event.getParameter("Who");
                        Value vDur = event.getParameter("Duration");
                        Value vDurUnit = event.getParameter("Duration Unit");

                        HRef id = treeId;
                        if (id == null) {
                            Value vId = event.getParameter("ID", ValueType.STRING);
                            id = Utils.idToRef(vId);
                        }

                        String sLevel = vLev.getString();
                        int level = Integer.parseInt(sLevel);
                        if (level < 1 || level > 17) {
                            throw new RuntimeException("Invalid level");
                        }

                        HVal val = null;
                        if (vValue != null) {
                            if (vVT == null) {
                                String err = "Missing value type";
                                throw new RuntimeException(err);
                            }
                            String stringVal = vValue.getString();
                            String type = vVT.getString();
                            switch (type) {
                                case "bool":
                                    boolean b = Boolean.parseBoolean(stringVal);
                                    val = HBool.make(b);
                                    break;
                                case "number":
                                    double num = Double.parseDouble(stringVal);
                                    String unit = null;
                                    if (vUnit != null) {
                                        unit = vUnit.getString();
                                    }
                                    val = HNum.make(num, unit);
                                    break;
                                case "str":
                                    val = HStr.make(stringVal);
                                    break;
                                default:
                                    String err = "Unknown type: " + type;
                                    throw new RuntimeException(err);
                            }
                        }

                        String who = null;
                        if (vWho != null) {
                            who = vWho.getString();
                        }

                        HNum dur = null;
                        if (vDur != null) {
                            if (vDurUnit == null) {
                                String err = "Missing duration unit";
                                throw new RuntimeException(err);
                            }
                            String unit = vDurUnit.getString();
                            dur = HNum.make(vDur.getNumber().intValue(), unit);
                        }
                        HGrid grid = client.pointWrite(id, level, who, val, dur);
                        HRow row = grid.row(level - 1);
                        Row r = new Row();

                        String[] res = new String[] {
                                "level",
                                "levelDis",
                                "val",
                                "who"
                        };
                        for (String s : res) {
                            val = row.get(s, false);
                            if (val != null) {
                                r.addValue(Utils.hvalToVal(val));
                            } else {
                                r.addValue(null);
                            }
                        }
                        event.getTable().addRow(r);
                    }
                });
            }
        });
        if (treeId == null) {
            Parameter p = new Parameter("ID", ValueType.STRING);
            p.setDescription("Haystack ref ID to write to.");
            a.addParameter(p);
        }
        {
            String[] enums = new String[17];
            for (int i = 0; i < enums.length; ++i) {
                enums[i] = String.valueOf(i + 1);
            }
            ValueType type = ValueType.makeEnum(enums);
            Parameter p = new Parameter("Level", type);
            p.setDescription("Number from 1-17 for level to write.");
            p.setDefaultValue(new Value("17"));
            a.addParameter(p);
        }
        {
            Parameter p = new Parameter("Value", ValueType.STRING);
            String msg = "Value to write or none to set the level ";
            msg += "back to auto.";
            p.setDescription(msg);
            a.addParameter(p);
        }
        {

            ValueType type = Utils.getHaystackTypes();
            Parameter p = new Parameter("Value Type", type);
            p.setDescription("Haystack value type.");
            p.setDefaultValue(new Value("str"));
            a.addParameter(p);
        }
        {

            Parameter p = new Parameter("Value Unit", ValueType.STRING);
            p.setDescription("Value unit, only affects number types.");
            a.addParameter(p);
        }
        {
            Parameter p = new Parameter("Who", ValueType.STRING);
            String msg = "optional username performing the write, ";
            msg += "otherwise user dis is used.";
            p.setDescription(msg);
            a.addParameter(p);
        }
        {
            Parameter p = new Parameter("Duration", ValueType.NUMBER);
            String msg = "Duration before level expires back to auto.";
            msg += " This takes effect when using a level of 8";
            p.setDescription(msg);
            a.addParameter(p);
        }
        {
            String[] enums = new String[] {
                    "ms",
                    "sec",
                    "min",
                    "hr",
                    "day",
                    "wk",
                    "mo",
                    "yr"
            };
            ValueType type = ValueType.makeEnum(enums);
            Parameter p = new Parameter("Duration Unit", type);
            p.setDefaultValue(new Value("hr"));
            p.setDescription("Duration unit.");
            a.addParameter(p);
        }
        {
            Parameter p = new Parameter("level", ValueType.NUMBER);
            a.addResult(p);
        }
        {
            Parameter p = new Parameter("levelDis", ValueType.STRING);
            a.addResult(p);
        }
        {
            Parameter p = new Parameter("val", ValueType.DYNAMIC);
            a.addResult(p);
        }
        {
            Parameter p = new Parameter("who", ValueType.STRING);
            a.addResult(p);
        }
        return a;
    }

    public static Action getReadAction(final Haystack haystack) {
        Action a = new Action(Permission.READ, new Handler<ActionResult>() {
            @Override
            public void handle(final ActionResult event) {
                Value vFilter = event.getParameter("filter", ValueType.STRING);
                Value vLimit = event.getParameter("limit");

                String filter = vFilter.getString();
                int limit = 1;
                if (vLimit != null) {
                    limit = vLimit.getNumber().intValue();
                }
                haystack.read(filter, limit, new Handler<HGrid>() {
                    @Override
                    public void handle(HGrid grid) {
                        if (grid != null) {
                            buildTable(grid, event);
                        }
                    }
                });

            }
        });
        a.addParameter(new Parameter("filter", ValueType.STRING));
        a.addParameter(new Parameter("limit", ValueType.NUMBER));
        a.setResultType(ResultType.TABLE);
        return a;
    }

    public static Action getEvalAction(final Haystack haystack) {
        Action a = new Action(Permission.READ, new Handler<ActionResult>() {
            @Override
            public void handle(final ActionResult event) {
                Value vExpr = event.getParameter("expr", ValueType.STRING);
                String expr = vExpr.getString();

                haystack.eval(expr, new Handler<HGrid>() {
                    @Override
                    public void handle(HGrid grid) {
                        buildTable(grid, event);
                    }
                });
            }
        });
        a.addParameter(new Parameter("expr", ValueType.STRING));
        a.setResultType(ResultType.TABLE);
        return a;
    }

    public static Action getHisReadAction(final Haystack haystack) {
        Action a = new Action(Permission.READ, new Handler<ActionResult>() {
            @Override
            public void handle(final ActionResult event) {
                Value vId = event.getParameter("id", ValueType.STRING);
                Value vRange = event.getParameter("range", ValueType.STRING);
                String id = vId.getString();
                String range = vRange.getString();

                HGridBuilder builder = new HGridBuilder();
                builder.addCol("id");
                builder.addCol("range");
                builder.addRow(new HVal[]{
                        HRef.make(id),
                        HStr.make(range)
                });

                haystack.call("hisRead", builder.toGrid(), new Handler<HGrid>() {
                    @Override
                    public void handle(HGrid grid) {
                        if (grid != null) {
                            buildTable(grid, event);
                        }
                    }
                });

            }
        });
        a.addParameter(new Parameter("id", ValueType.STRING));
        a.addParameter(new Parameter("range", ValueType.STRING));
        a.setResultType(ResultType.TABLE);
        return a;
    }

    private static void buildTable(HGrid in, ActionResult out) {
        Table t = out.getTable();
        for (int i = 0; i < in.numCols(); i++) {
            String name = in.col(i).name();
            Parameter p = new Parameter(name, ValueType.DYNAMIC);
            t.addColumn(p);
        }

        Iterator it = in.iterator();
        while (it.hasNext()) {
            HRow hRow = (HRow) it.next();
            Row row = new Row();
            for (int i = 0; i < in.numCols(); i++) {
                HVal val = hRow.get(in.col(i), false);
                if (val != null) {
                    Value value = Utils.hvalToVal(val);
                    row.addValue(value);
                } else {
                    row.addValue(null);
                }
            }
            t.addRow(row);
        }
    }
}
