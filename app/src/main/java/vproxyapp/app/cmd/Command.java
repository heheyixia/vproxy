package vproxyapp.app.cmd;

import vfd.IP;
import vproxy.component.proxy.Session;
import vproxy.component.secure.SecurityGroup;
import vproxy.component.secure.SecurityGroupRule;
import vproxyapp.app.Application;
import vproxyapp.app.cmd.handle.resource.*;
import vproxybase.Config;
import vproxybase.dns.Cache;
import vproxybase.util.Callback;
import vproxybase.util.LogType;
import vproxybase.util.Logger;
import vswitch.IPMac;
import vswitch.iface.Iface;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

public class Command {
    public Action action;
    public Resource resource;
    public Preposition preposition;
    public Resource prepositionResource;
    public final List<Flag> flags = new LinkedList<>();
    public final Map<Param, String> args = new HashMap<>();

    public static String helpString() {
        return HelpCommand.helpString();
    }

    public static Command parseStrCmd(String line) throws Exception {
        List<String> cmd = Arrays.asList(line.trim().split(" "));
        return parseStrCmd(cmd);
    }

    public static Command parseStrCmd(List<String> _cmd) throws Exception {
        // this string builder is only used for log, not the parsing process
        StringBuilder sb = new StringBuilder();
        for (String c : _cmd) {
            sb.append(c);
            sb.append(" "); // the last space is not visible, print it any way
        }
        assert Logger.lowLevelDebug(LogType.BEFORE_PARSING_CMD + " - " + sb.toString());

        Command cmd = statm(_cmd);
        semantic(cmd);
        assert Logger.lowLevelDebug(LogType.AFTER_PARSING_CMD + " - " + cmd.toString());

        return cmd;
    }

    @SuppressWarnings("DuplicateBranchesInSwitch")
    public static Command statm(List<String> _cmd) throws Exception {
        _cmd = _cmd.stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        Command cmd = new Command();

        int state;
        // 0 --> initial state, expecting Action (->1)
        // 1 --> action found, expecting resource type (->2)
        // 2 --> resource type found, expecting resource name (->3) or `in`(->10) or end
        // 3 --> resource name found, expecting preposition(->4) or flags(->7) or params(->8) or `in`(->10) or end
        // 4 --> preposition found, expecting resource type(-> 5)
        // 5 --> resource type found after preposition, expecting resource name(->6)
        // 6 --> resource name found after preposition, expecting flags(->7) or params(->8) or `in`(->13) or end
        // 7 --> flags found, expecting flags(->7) or params(->8) or end
        // 8 --> params found, expecting value (->9)
        // 9 --> value found, expecting flags(->7) or params(->8) or end
        // 10 --> `in` found, expecting resource type (->11)
        // 11 --> resource type found after in, expecting resource name (->12)
        // 12 --> resource name found after in, expecting preposition(->4) or flags(->7) or params(->8) or `in`(->10) or end
        // 13 --> `in` found after preposition, expecting resource type (->14)
        // 14 --> resource type found after preposition and in, expecting resource name (->15)
        // 15 --> resource name found after preposition and in, expecting flags(->7) or params(->8) or `in`(->13) or end

        Resource lastResource = null;
        Param lastParam = null;
        // --------------------------------------------------------------
        // implementation should follow the state machine commented above
        // --------------------------------------------------------------

        state = 1;
        loop:
        for (int i = 0; i < _cmd.size(); i++) {
            String c = _cmd.get(i);
            String next = (_cmd.size() - 1 == i) ? null : _cmd.get(i + 1);
            switch (state) {
                case 1:
                    try {
                        cmd.action = getEnum(c, Action.class);
                    } catch (IllegalArgumentException e) {
                        throw new Exception(c + " is not a valid action");
                    }
                    state = 2;
                    notEnd(next);
                    break;
                case 2:
                    lastResource = new Resource();
                    cmd.resource = lastResource;
                    try {
                        lastResource.type = getEnum(c, ResourceType.class);
                    } catch (IllegalArgumentException e) {
                        throw new Exception(c + " is not a valid resource type");
                    }
                    if (next == null) { // end
                        break loop;
                    }
                    if (next.equals("in")) {
                        state = 10;
                        break;
                    } else {
                        state = 3;
                        break;
                    }
                case 3:
                    lastResource.alias = validName(c);
                    if (next == null) { // end
                        break loop;
                    }
                    if (isEnumMatch(next, Preposition.class)) {
                        state = 4;
                        break;
                    }
                    if (isEnumMatch(next, Flag.class)) {
                        state = 7;
                        break;
                    }
                    if (isEnumMatch(next, Param.class)) {
                        state = 8;
                        break;
                    }
                    if (next.equals("in")) {
                        state = 10;
                        break;
                    }
                    throw new Exception("invalid syntax near " + c + " " + next);
                case 4:
                    try {
                        cmd.preposition = getEnum(c, Preposition.class);
                    } catch (IllegalArgumentException e) {
                        throw new Exception(c + " is not a valid preposition");
                    }
                    state = 5;
                    notEnd(next);
                    break;
                case 5:
                    lastResource = new Resource();
                    cmd.prepositionResource = lastResource;
                    try {
                        lastResource.type = getEnum(c, ResourceType.class);
                    } catch (IllegalArgumentException e) {
                        throw new Exception(c + " is not a valid resource type");
                    }
                    state = 6;
                    notEnd(next);
                    break;
                case 6:
                    lastResource.alias = validName(c);
                    if (next == null) { // end
                        break loop;
                    }
                    if (isEnumMatch(next, Flag.class)) {
                        state = 7;
                        break;
                    }
                    if (isEnumMatch(next, Param.class)) {
                        state = 8;
                        break;
                    }
                    if (next.equals("in")) {
                        state = 13;
                        break;
                    }
                    throw new Exception("invalid syntax near " + c + " " + next);
                case 7:
                    try {
                        cmd.flags.add(getEnum(c, Flag.class));
                    } catch (IllegalArgumentException e) {
                        throw new Exception(c + " is not a valid flag");
                    }
                    if (next == null) {
                        break loop;
                    }
                    if (isEnumMatch(next, Flag.class)) {
                        state = 7;
                        break;
                    }
                    if (isEnumMatch(next, Param.class)) {
                        state = 8;
                        break;
                    }
                    throw new Exception("invalid syntax near " + c + " " + next);
                case 8:
                    try {
                        lastParam = getEnum(c, Param.class);
                    } catch (Exception e) {
                        throw new Exception(c + " is not a valid param");
                    }
                    state = 9;
                    notEnd(next);
                    break;
                case 9:
                    String v = validName(c);
                    cmd.args.put(lastParam, v);
                    if (next == null) {
                        break loop;
                    }
                    if (isEnumMatch(next, Flag.class)) {
                        state = 7;
                        break;
                    }
                    if (isEnumMatch(next, Param.class)) {
                        state = 8;
                        break;
                    }
                    throw new Exception("invalid syntax near " + c + " " + next);
                case 10:
                    state = 11;
                    notEnd(next);
                    break;
                case 11:
                    lastResource.parentResource = new Resource();
                    lastResource = lastResource.parentResource;
                    try {
                        lastResource.type = getEnum(c, ResourceType.class);
                    } catch (IllegalArgumentException e) {
                        throw new Exception(c + " is not a valid resource type");
                    }
                    state = 12;
                    notEnd(next);
                    break;
                case 12:
                    lastResource.alias = validName(c);
                    if (next == null) {
                        break loop;
                    }
                    if (isEnumMatch(next, Preposition.class)) {
                        state = 4;
                        break;
                    }
                    if (isEnumMatch(next, Flag.class)) {
                        state = 7;
                        break;
                    }
                    if (isEnumMatch(next, Param.class)) {
                        state = 8;
                        break;
                    }
                    if (next.equals("in")) {
                        state = 10;
                        break;
                    }
                    throw new Exception("invalid syntax near " + c + " " + next);
                case 13:
                    state = 14;
                    notEnd(next);
                    break;
                case 14:
                    lastResource.parentResource = new Resource();
                    lastResource = lastResource.parentResource;
                    try {
                        lastResource.type = getEnum(c, ResourceType.class);
                    } catch (IllegalArgumentException e) {
                        throw new Exception(c + " is not a valid resource type");
                    }
                    state = 15;
                    notEnd(next);
                    break;
                case 15:
                    lastResource.alias = validName(c);
                    if (next == null) { // end
                        break loop;
                    }
                    if (isEnumMatch(next, Flag.class)) {
                        state = 7;
                        break;
                    }
                    if (isEnumMatch(next, Param.class)) {
                        state = 8;
                        break;
                    }
                    if (next.equals("in")) {
                        state = 13;
                        break;
                    }
                    throw new Exception("invalid syntax near " + c + " " + next);
            }
        }
        if (state != 2 && state != 3 && state != 6 && state != 7 && state != 9 && state != 12 && state != 15) {
            throw new Exception("the parser has a bug and did not detect invalidity of this input");
        }

        return cmd;
    }

    private static String validName(String c) throws Exception {
        if (c.contains(" ") || c.contains("\t"))
            throw new Exception(c + " is invalid");
        return c;
    }

    private static void notEnd(String next) throws Exception {
        if (next == null)
            throw new Exception("unexpected end");
    }

    private static <T extends Enum<T>> boolean isEnumMatch(String c, Class<T> cls) throws Exception {
        try {
            getEnum(c, cls);
            return true;
        } catch (IllegalArgumentException ignore) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Enum<T>> T getEnum(String c, Class<T> cls) throws Exception {
        for (Enum e : cls.getEnumConstants()) {
            if (e.name().equals(c))
                return (T) e;
        }
        try {
            Field f = cls.getField("fullname");
            for (Enum e : cls.getEnumConstants()) {
                if (f.get(e).equals(c))
                    return (T) e;
            }
        } catch (NoSuchFieldException ignore) {
        }
        throw new IllegalArgumentException();
    }

    private static void semantic(Command cmd) throws Exception {
        if (cmd.action == Action.l || cmd.action == Action.L) {
            // for list operations, to/from are not allowed
            if (cmd.preposition != null) {
                throw new Exception("cannot specify preposition when action is " + Action.l.fullname + " or " + Action.L.fullname);
            }
            // and obviously you cannot specify a name when retrieving a list
            if (cmd.resource.alias != null) {
                throw new Exception("cannot specify resource name when " + cmd.action.fullname + "(-ing) the resource");
            }
        } else {
            // for non list operations, i.e. modification operations
            // check whether config is allowed to be modified
            if (Config.configModifyDisabled) {
                throw new Exception("modifying is disabled");
            }
            // the name to operate is required
            if (cmd.resource.alias == null) {
                throw new Exception("resource name not specified when " + cmd.action.fullname + "(-ing) the resource");
            }
            if (cmd.preposition != null) {
                // for add operations, preposition should be `to`
                // for remove operations, preposition should be `from`
                if ((cmd.action == Action.a && cmd.preposition != Preposition.to)
                    ||
                    ((cmd.action == Action.r || cmd.action == Action.R) && cmd.preposition != Preposition.from)
                )
                    throw new Exception("cannot " + cmd.action.fullname + "(-ing) " + cmd.resource.type.fullname + " [" + cmd.preposition + "] " + cmd.prepositionResource.type.fullname);
            }
            // for add/remove operations, the resource should not have a parent resource
            // the parent resource should be specified in preposition if required
            if (cmd.action == Action.a || cmd.action == Action.r || cmd.action == Action.R) {
                if (cmd.resource.parentResource != null) {
                    throw new Exception("cannot specify parent resource when " + cmd.action.fullname + "(-ing) " + cmd.resource.type.fullname);
                }
            }
            // for update operations, the preposition should not exist
            // the parent resource may exist
            if (cmd.action == Action.u) {
                if (cmd.prepositionResource != null) {
                    throw new Exception("cannot use " + cmd.preposition + " when " + cmd.action.fullname + "(-ing) " + cmd.resource.type.fullname);
                }
            }
        }
        Resource targetResource = cmd.resource.parentResource;
        if (targetResource == null)
            targetResource = cmd.prepositionResource;
        switch (cmd.resource.type) {
            case ss: // serverSock
            case conn: // connection
            case sess: // session
                // these resources are related to a channel
                // the handles are similar
                // so put them together
                // though there're still some logic branches
                switch1:
                switch (cmd.action) {
                    case a:
                    case r:
                        // not supported for these resources
                        throw new Exception("cannot run " + cmd.action.fullname + " on " + cmd.resource.type.fullname);
                    case R:
                        if (cmd.resource.type == ResourceType.ss)
                            throw new Exception("cannot run " + cmd.action.fullname + " on " + cmd.resource.type.fullname);
                    case L:
                    case l:
                        switch (cmd.resource.type) {
                            case ss:
                                ServerSockHandle.checkServerSockParent(targetResource);
                                break switch1;
                            case conn:
                                ConnectionHandle.checkConnectionParent(targetResource);
                                break switch1;
                            case sess:
                                SessionHandle.checkSession(targetResource);
                                break switch1;
                            // no need to add default here
                        }
                    default:
                        throw new Exception("unsupported action " + cmd.action.fullname + " for " + cmd.resource.type.fullname);
                }
                break;
            case bin: // bytes-in
            case bout: // bytes-out
                bsw:
                switch (cmd.action) {
                    case a:
                    case r:
                    case R:
                        // modification not supported for accepted-connections count resources
                        throw new Exception("cannot run " + cmd.action.fullname + " on " + cmd.resource.type.fullname);
                    case L:
                    case l:
                        if (targetResource == null)
                            throw new Exception("cannot find " + cmd.resource.type.fullname + " on top level");
                        switch (targetResource.type) {
                            case ss:
                                ServerSockHandle.checkServerSockParent(targetResource);
                                break bsw;
                            case conn:
                                ConnectionHandle.checkConnectionParent(targetResource);
                                break bsw;
                            case svr:
                                ServerHandle.checkServer(targetResource);
                                break bsw;
                            default:
                                throw new Exception(targetResource.type.fullname + " does not contain " + cmd.resource.type.fullname);
                        }
                    default:
                        throw new Exception("unsupported action " + cmd.action.fullname + " for " + cmd.resource.type.fullname);
                }
                break;
            case acceptedconncount: // accepted-connections
                switch (cmd.action) {
                    case a:
                    case r:
                    case R:
                        // modification not supported for accepted-connections count resources
                        throw new Exception("cannot run " + cmd.action.fullname + " on " + cmd.resource.type.fullname);
                    case L:
                    case l:
                        // can be found in server-sock
                        if (targetResource == null)
                            throw new Exception("cannot find " + cmd.resource.type.fullname + " on top level");
                        ServerSockHandle.checkServerSockParent(targetResource.parentResource);
                        break;
                    default:
                        throw new Exception("unsupported action " + cmd.action.fullname + " for " + cmd.resource.type.fullname);
                }
                break;
            case el: // event loop
                switch (cmd.action) {
                    case a:
                    case r:
                    case R:
                    case L:
                    case l:
                        // event loop should be found in event loop group
                        if (targetResource == null)
                            throw new Exception("cannot find " + cmd.resource.type.fullname + " on top level");
                        if (targetResource.type != ResourceType.elg)
                            throw new Exception(targetResource.type.fullname + " does not contain " + cmd.resource.type.fullname);
                        // also should check event loop group
                        EventLoopGroupHandle.checkEventLoopGroup(targetResource);
                        // no need to check for creation
                        // no param required
                        break;
                    default:
                        throw new Exception("unsupported action " + cmd.action.fullname + " for " + cmd.resource.type.fullname);
                }
                break;
            case svr: // server
                switch (cmd.action) {
                    case a:
                    case r:
                    case R:
                    case L:
                    case l:
                        // server should be found in server group
                        if (targetResource == null)
                            throw new Exception("cannot find " + cmd.resource.type.fullname + " on top level");
                        if (targetResource.type != ResourceType.sg)
                            throw new Exception(targetResource.type.fullname + " does not contain " + cmd.resource.type.fullname);
                        // also should check server group
                        ServerGroupHandle.checkServerGroup(targetResource);
                        // check for creation
                        if (cmd.action == Action.a) {
                            ServerHandle.checkCreateServer(cmd);
                        }
                        break;
                    case u:
                        if (targetResource == null)
                            throw new Exception("cannot find " + cmd.resource.type.fullname + " on top level");
                        ServerHandle.checkUpdateServer(cmd);
                        break;
                    default:
                        throw new Exception("unsupported action " + cmd.action.fullname + " for " + cmd.resource.type.fullname);
                }
                break;
            case sg: // server group
                switch (cmd.action) {
                    case a:
                    case r:
                    case R:
                    case L:
                    case l:
                        // server group is on top level or in upstream
                        if (targetResource != null) {
                            if (targetResource.type != ResourceType.ups)
                                throw new Exception(targetResource.type.fullname + " does not contain " + cmd.resource.type.fullname);
                            // also should check upstream
                            UpstreamHandle.checkUpstream(targetResource);
                            if (cmd.action == Action.a) {
                                // also should check server group
                                ServerGroupHandle.checkAttachServerGroup(cmd);
                            }
                        } else {
                            // only check creation when on top level
                            if (cmd.action == Action.a) {
                                ServerGroupHandle.checkCreateServerGroup(cmd);
                            }
                        }
                        break;
                    case u:
                        ServerGroupHandle.checkUpdateServerGroup(cmd);
                        break;
                    default:
                        throw new Exception("unsupported action " + cmd.action.fullname + " for " + cmd.resource.type.fullname);
                }
                break;
            case secgr: // security group rule
                switch (cmd.action) {
                    case a:
                    case r:
                    case R:
                    case L:
                    case l:
                        // security group rule is in security group
                        if (targetResource == null)
                            throw new Exception("cannot find " + cmd.resource.type.fullname + " on top level");
                        if (targetResource.type != ResourceType.secg)
                            throw new Exception(targetResource.type.fullname + " does not contain " + cmd.resource.type.fullname);
                        SecurityGroupHandle.checkSecurityGroup(targetResource);
                        if (cmd.action == Action.a) {
                            SecurityGroupRuleHandle.checkCreateSecurityGroupRule(cmd);
                        }
                        break;
                    default:
                        throw new Exception("unsupported action " + cmd.action.fullname + " for " + cmd.resource.type.fullname);
                }
                break;
            case dnscache:
                switch (cmd.action) {
                    case a:
                    case r:
                        throw new Exception("cannot run " + cmd.action.fullname + " on " + cmd.resource.type.fullname);
                    case R:
                    case L:
                    case l:
                        DnsCacheHandle.checkDnsCacheParent(targetResource);
                        break;
                    default:
                        throw new Exception("unsupported action " + cmd.action.fullname + " for " + cmd.resource.type.fullname);
                }
                break;
            case ups: // upstream
            case tl: // tcp lb
            case socks5: // socks5 server
            case dns:
            case sw:
            case elg: // event loog group
            case secg: // security group
                // these four are only exist on top level
                // so bring them together
                switch (cmd.action) {
                    case a:
                    case r:
                    case R:
                    case L:
                    case l:
                    case u:
                        if (cmd.resource.type == ResourceType.sw) {
                            if (targetResource != null && targetResource.type != ResourceType.sw)
                                throw new Exception(cmd.resource.type.fullname + " is on top level or as a remote switch in " + ResourceType.sw.fullname);
                        } else {
                            if (targetResource != null)
                                throw new Exception(cmd.resource.type.fullname + " is on top level");
                        }
                        // only check creation for tcp lb and secg
                        // the other two does not have creation param
                        if (cmd.action == Action.a) {
                            if (cmd.resource.type == ResourceType.tl) {
                                TcpLBHandle.checkCreateTcpLB(cmd);
                            } else if (cmd.resource.type == ResourceType.socks5) {
                                Socks5ServerHandle.checkCreateSocks5Server(cmd);
                            } else if (cmd.resource.type == ResourceType.dns) {
                                DNSServerHandle.checkCreateDNSServer(cmd);
                            } else if (cmd.resource.type == ResourceType.sw) {
                                SwitchHandle.checkCreateSwitch(cmd);
                            } else if (cmd.resource.type == ResourceType.secg) {
                                SecurityGroupHandle.checkCreateSecurityGroup(cmd);
                            } // the other two does not need check
                        }
                        if (cmd.action == Action.u) {
                            if (cmd.resource.type == ResourceType.tl) {
                                TcpLBHandle.checkUpdateTcpLB(cmd);
                            } else if (cmd.resource.type == ResourceType.socks5) {
                                Socks5ServerHandle.checkUpdateSocks5Server(cmd);
                            } else if (cmd.resource.type == ResourceType.dns) {
                                DNSServerHandle.checkUpdateDNSServer(cmd);
                            } else if (cmd.resource.type == ResourceType.sw) {
                                SwitchHandle.checkUpdateSwitch(cmd);
                            } else if (cmd.resource.type != ResourceType.secg)
                                throw new Exception("unsupported action " + cmd.action.fullname + " for " + cmd.resource.type.fullname);
                        }
                        break;
                    default:
                        throw new Exception("unsupported action " + cmd.action.fullname + " for " + cmd.resource.type.fullname);
                }
                break;
            case resolver:
                // disallow all operations on resolver
                throw new Exception("cannot run " + cmd.action.fullname + " on " + cmd.resource.type.fullname);
            case ck:
                switch (cmd.action) {
                    case a:
                    case r:
                    case R:
                    case L:
                    case l:
                        CertKeyHandle.checkCertKey(cmd.resource);
                        if (cmd.action == Action.a) {
                            CertKeyHandle.checkAddCertKey(cmd);
                        }
                        break;
                    default:
                        throw new Exception("unsupported action " + cmd.action.fullname + " for " + cmd.resource.type.fullname);
                }
                break;
            case arp:
            case iface:
                switch (cmd.action) {
                    case a:
                    case r:
                    case R:
                        throw new Exception("cannot run " + cmd.action.fullname + " on " + cmd.resource.type.fullname);
                    case L:
                    case l:
                        if (cmd.resource.type == ResourceType.arp) {
                            ArpHandle.checkArpParent(targetResource);
                        } else {
                            //noinspection ConstantConditions
                            assert cmd.resource.type == ResourceType.iface;
                            IfaceHandle.checkIfaceParent(targetResource);
                        }
                        break;
                    default:
                        throw new Exception("unsupported action " + cmd.action.fullname + " for " + cmd.resource.type.fullname);
                }
                break;
            case user:
            case vpc:
            case route:
            case ip:
            case proxy:
                switch (cmd.action) {
                    case a:
                        if (cmd.resource.type == ResourceType.user) {
                            UserHandle.checkCreateUser(cmd);
                        } else if (cmd.resource.type == ResourceType.vpc) {
                            VpcHandle.checkCreateVpc(cmd);
                        } else if (cmd.resource.type == ResourceType.route) {
                            RouteHandle.checkCreateRoute(cmd);
                        } else if (cmd.resource.type == ResourceType.ip) {
                            IpHandle.checkCreateIp(cmd);
                        } else {
                            ProxyHandle.checkCreateSwitchProxy(cmd);
                        }
                    case r:
                    case R:
                        if (cmd.action == Action.r || cmd.action == Action.R) {
                            if (cmd.resource.type == ResourceType.ucli) {
                                UserClientHandle.checkRemoveUserClient(cmd);
                            } else if (cmd.resource.type == ResourceType.proxy) {
                                ProxyHandle.checkRemoveSwitchProxy(cmd);
                            }
                        }
                    case L:
                    case l:
                        if (cmd.resource.type == ResourceType.user) {
                            UserHandle.checkUserParent(targetResource);
                        } else if (cmd.resource.type == ResourceType.vpc) {
                            VpcHandle.checkVpcParent(targetResource);
                        } else if (cmd.resource.type == ResourceType.route) {
                            RouteHandle.checkRouteParent(targetResource);
                        } else if (cmd.resource.type == ResourceType.ip) {
                            IpHandle.checkIpParent(targetResource);
                        } else {
                            ProxyHandle.checkSwitchProxyParent(targetResource);
                        }
                        break;
                    default:
                        throw new Exception("unsupported action " + cmd.action.fullname + " for " + cmd.resource.type.fullname);
                }
                break;
            case ucli:
            case tap:
                switch (cmd.action) {
                    case a:
                        if (cmd.resource.type == ResourceType.ucli) {
                            UserClientHandle.checkCreateUserClient(cmd);
                        } else {
                            TapHandle.checkCreateTap(cmd);
                        }
                    case r:
                    case R:
                        if (cmd.action == Action.r || cmd.action == Action.R) {
                            if (cmd.resource.type == ResourceType.ucli) {
                                UserClientHandle.checkRemoveUserClient(cmd);
                            }
                        }
                        if (cmd.resource.type == ResourceType.ucli) {
                            UserClientHandle.checkUserClientParent(targetResource);
                        } else {
                            TapHandle.checkTapParent(targetResource);
                        }
                        break;
                    default:
                        throw new Exception("unsupported action " + cmd.action.fullname + " for " + cmd.resource.type.fullname);
                }
                break;
            default:
                throw new Exception("unknown resource type " + cmd.resource.type.fullname);
        }
    }

    public void run(Callback<CmdResult, Throwable> cb) {
        Application.get().controlEventLoop.getSelectorEventLoop().nextTick(() -> {
            CmdResult res;
            try {
                res = runThrow();
            } catch (Throwable e) {
                cb.failed(e);
                return;
            }
            cb.succeeded(res);
        });
    }

    private static String utilJoinList(List<?> ls) {
        StringBuilder sb = new StringBuilder();
        boolean isFirst = true;
        for (Object o : ls) {
            if (isFirst) {
                isFirst = false;
            } else {
                sb.append("\n");
            }
            sb.append(o);
        }
        return sb.toString();
    }

    private CmdResult runThrow() throws Exception {
        Resource targetResource = resource.parentResource == null ? prepositionResource : resource.parentResource;
        switch (resource.type) {
            case conn: // can be retrieved from tl or el
                switch (action) {
                    case l:
                        int connCount = ConnectionHandle.count(targetResource);
                        return new CmdResult(connCount, connCount, "" + connCount);
                    case L:
                        List<ConnectionHandle.Conn> connList = ConnectionHandle.list(targetResource);
                        List<String> connStrList = connList.stream().map(ConnectionHandle.Conn::toString).collect(Collectors.toList());
                        return new CmdResult(connStrList, connStrList, utilJoinList(connList));
                    case R:
                        ConnectionHandle.close(this);
                        return new CmdResult();
                }
            case sess: // can only be retrieve from tl
                switch (action) {
                    case l:
                        int sessCount = SessionHandle.count(targetResource);
                        return new CmdResult(sessCount, sessCount, "" + sessCount);
                    case L:
                        List<Session> sessList = SessionHandle.list(targetResource);
                        List<List<String>> sessTupleList = sessList.stream().map(sess ->
                            Arrays.asList(
                                new ConnectionHandle.Conn(sess.active, true).toString(),
                                new ConnectionHandle.Conn(sess.passive, false).toString()
                            )).collect(Collectors.toList());
                        return new CmdResult(sessList, sessTupleList, utilJoinList(sessList));
                    case R:
                        SessionHandle.close(this);
                        return new CmdResult();
                }
            case ss: // can only be retrieved from el
                switch (action) {
                    case l:
                        int bsCount = ServerSockHandle.count(targetResource);
                        return new CmdResult(bsCount, bsCount, "" + bsCount);
                    case L:
                        List<ServerSockHandle.ServerSock2> bsList = ServerSockHandle.list(targetResource);
                        List<String> bsStrList = bsList.stream().map(ServerSockHandle.ServerSock2::toString).collect(Collectors.toList());
                        return new CmdResult(bsList, bsStrList, utilJoinList(bsList));
                }
            case bin:
                switch (action) {
                    case l:
                    case L:
                        long binRes = StatisticHandle.bytesIn(targetResource);
                        return new CmdResult(binRes, binRes, "" + binRes);
                }
            case bout:
                switch (action) {
                    case l:
                    case L:
                        long boutRes = StatisticHandle.bytesOut(targetResource);
                        return new CmdResult(boutRes, boutRes, "" + boutRes);
                }
            case acceptedconncount:
                switch (action) {
                    case l:
                    case L:
                        long acc = StatisticHandle.acceptedConnCount(targetResource);
                        return new CmdResult(acc, acc, "" + acc);
                }
            case svr: // can only be retrieved from server group
                switch (action) {
                    case l:
                        List<String> serverNames = ServerHandle.names(targetResource);
                        return new CmdResult(serverNames, serverNames, utilJoinList(serverNames));
                    case L:
                        List<ServerHandle.ServerRef> svrRefList = ServerHandle.detail(targetResource);
                        List<String> svrRefStrList = svrRefList.stream().map(Object::toString).collect(Collectors.toList());
                        return new CmdResult(svrRefList, svrRefStrList, utilJoinList(svrRefList));
                    case a:
                        ServerHandle.add(this);
                        return new CmdResult();
                    case r:
                    case R:
                        ServerHandle.forceRemove(this);
                        return new CmdResult();
                    case u:
                        ServerHandle.update(this);
                        return new CmdResult();
                }
            case ups: // top level
                switch (action) {
                    case l:
                    case L:
                        List<String> upsNames = UpstreamHandle.names();
                        return new CmdResult(upsNames, upsNames, utilJoinList(upsNames));
                    case a:
                        UpstreamHandle.add(this);
                        return new CmdResult();
                    case r:
                        UpstreamHandle.preRemoveCheck(this);
                    case R:
                        UpstreamHandle.forceRemove(this);
                        return new CmdResult();
                }
            case elg: // top level
                switch (action) {
                    case l:
                    case L:
                        List<String> elgNames = EventLoopGroupHandle.names();
                        return new CmdResult(elgNames, elgNames, utilJoinList(elgNames));
                    case a:
                        EventLoopGroupHandle.add(this);
                        return new CmdResult();
                    case r:
                        EventLoopGroupHandle.preRemoveCheck(this);
                    case R:
                        EventLoopGroupHandle.forceRemvoe(this);
                        return new CmdResult();
                }
            case el: // can only be retrieved from event loop group
                switch (action) {
                    case l:
                    case L:
                        List<String> elNames = EventLoopHandle.names(targetResource);
                        return new CmdResult(elNames, elNames, utilJoinList(elNames));
                    case a:
                        EventLoopHandle.add(this);
                        return new CmdResult();
                    case r:
                    case R:
                        EventLoopHandle.forceRemove(this);
                        return new CmdResult();
                }
            case sg: // top level or retrieved from upstream
                switch (action) {
                    case l:
                        List<String> sgNames = ServerGroupHandle.names(targetResource);
                        return new CmdResult(sgNames, sgNames, utilJoinList(sgNames));
                    case L:
                        List<ServerGroupHandle.ServerGroupRef> refs = ServerGroupHandle.details(targetResource);
                        List<String> refStrList = refs.stream().map(ServerGroupHandle.ServerGroupRef::toString).collect(Collectors.toList());
                        return new CmdResult(refs, refStrList, utilJoinList(refStrList));
                    case a:
                        ServerGroupHandle.add(this);
                        return new CmdResult();
                    case r:
                        ServerGroupHandle.preRemoveCheck(this);
                    case R:
                        ServerGroupHandle.forceRemove(this);
                        return new CmdResult();
                    case u:
                        ServerGroupHandle.update(this);
                        return new CmdResult();
                }
            case tl: // tcp loadbalancer on top level
                switch (action) {
                    case l:
                        List<String> tlNames = TcpLBHandle.names();
                        return new CmdResult(tlNames, tlNames, utilJoinList(tlNames));
                    case L:
                        List<TcpLBHandle.TcpLBRef> tlRefList = TcpLBHandle.details();
                        List<String> tlRefStrList = tlRefList.stream().map(Object::toString).collect(Collectors.toList());
                        return new CmdResult(tlRefList, tlRefStrList, utilJoinList(tlRefList));
                    case a:
                        TcpLBHandle.add(this);
                        return new CmdResult();
                    case r:
                    case R:
                        TcpLBHandle.forceRemove(this);
                        return new CmdResult();
                    case u:
                        TcpLBHandle.update(this);
                        return new CmdResult();
                }
                throw new Exception("cannot run " + action.fullname + " on " + resource.type.fullname);
            case socks5: // socks5 proxy server
                switch (action) {
                    case l:
                        List<String> socks5Names = Socks5ServerHandle.names();
                        return new CmdResult(socks5Names, socks5Names, utilJoinList(socks5Names));
                    case L:
                        List<Socks5ServerHandle.Socks5ServerRef> socks5RefList = Socks5ServerHandle.details();
                        List<String> socks5RefStrList = socks5RefList.stream().map(Object::toString).collect(Collectors.toList());
                        return new CmdResult(socks5RefList, socks5RefStrList, utilJoinList(socks5RefList));
                    case a:
                        Socks5ServerHandle.add(this);
                        return new CmdResult();
                    case r:
                    case R:
                        Socks5ServerHandle.forceRemove(this);
                        return new CmdResult();
                    case u:
                        Socks5ServerHandle.update(this);
                        return new CmdResult();
                }
                throw new Exception("cannot run " + action.fullname + " on " + resource.type.fullname);
            case dns:
                switch (action) {
                    case l:
                        List<String> dnsServerNames = DNSServerHandle.names();
                        return new CmdResult(dnsServerNames, dnsServerNames, utilJoinList(dnsServerNames));
                    case L:
                        List<DNSServerHandle.DNSServerRef> dnsServerRefList = DNSServerHandle.details();
                        List<String> dnsServerRefStrList = dnsServerRefList.stream().map(Object::toString).collect(Collectors.toList());
                        return new CmdResult(dnsServerRefStrList, dnsServerRefStrList, utilJoinList(dnsServerRefList));
                    case a:
                        DNSServerHandle.add(this);
                        return new CmdResult();
                    case r:
                    case R:
                        DNSServerHandle.forceRemove(this);
                        return new CmdResult();
                    case u:
                        DNSServerHandle.update(this);
                        return new CmdResult();
                }
                throw new Exception("cannot run " + action.fullname + " on " + resource.type.fullname);
            case sw:
                switch (action) {
                    case l:
                        List<String> swNames = SwitchHandle.names();
                        return new CmdResult(swNames, swNames, utilJoinList(swNames));
                    case L:
                        List<SwitchHandle.SwitchRef> swRefList = SwitchHandle.details();
                        List<String> swRefStrList = swRefList.stream().map(Object::toString).collect(Collectors.toList());
                        return new CmdResult(swRefList, swRefStrList, utilJoinList(swRefList));
                    case a:
                        SwitchHandle.add(this);
                        return new CmdResult();
                    case r:
                    case R:
                        SwitchHandle.forceRemove(this);
                        return new CmdResult();
                    case u:
                        SwitchHandle.update(this);
                        return new CmdResult();
                }
                throw new Exception("cannot run " + action.fullname + " on " + resource.type.fullname);
            case secg:
                switch (action) {
                    case l:
                        List<String> sgNames = SecurityGroupHandle.names();
                        return new CmdResult(sgNames, sgNames, utilJoinList(sgNames));
                    case L:
                        List<SecurityGroup> secg = SecurityGroupHandle.detail();
                        List<String> secgStrList = secg.stream().map(Object::toString).collect(Collectors.toList());
                        return new CmdResult(secgStrList, secgStrList, utilJoinList(secg));
                    case a:
                        SecurityGroupHandle.add(this);
                        return new CmdResult();
                    case u:
                        SecurityGroupHandle.update(this);
                        return new CmdResult();
                    case r:
                        SecurityGroupHandle.preRemoveCheck(this);
                    case R:
                        SecurityGroupHandle.forceRemove(this);
                        return new CmdResult();
                }
            case secgr:
                switch (action) {
                    case l:
                        List<String> ruleNames = SecurityGroupRuleHandle.names(targetResource);
                        return new CmdResult(ruleNames, ruleNames, utilJoinList(ruleNames));
                    case L:
                        List<SecurityGroupRule> rules = SecurityGroupRuleHandle.detail(targetResource);
                        List<String> ruleStrList = rules.stream().map(Object::toString).collect(Collectors.toList());
                        return new CmdResult(rules, ruleStrList, utilJoinList(rules));
                    case a:
                        SecurityGroupRuleHandle.add(this);
                        return new CmdResult();
                    case r:
                    case R:
                        SecurityGroupRuleHandle.forceRemove(this);
                        return new CmdResult();
                }
            case dnscache:
                switch (action) {
                    case l:
                        int cacheCnt = DnsCacheHandle.count();
                        return new CmdResult(cacheCnt, cacheCnt, "" + cacheCnt);
                    case L:
                        List<Cache> caches = DnsCacheHandle.detail();
                        List<Object> cacheStrList = caches.stream().map(c -> Arrays.asList(
                            c.host,
                            c.ipv4.stream().map(IP::formatToIPString).collect(Collectors.toList()),
                            c.ipv6.stream().map(IP::formatToIPString).collect(Collectors.toList())
                        )).collect(Collectors.toList());
                        return new CmdResult(caches, cacheStrList, utilJoinList(caches));
                    case R:
                        DnsCacheHandle.remove(this);
                        return new CmdResult();
                }
            case vpc:
                switch (action) {
                    case l:
                        List<VpcHandle.VpcEntry> vpcLs = VpcHandle.list(targetResource);
                        List<Object> ls = vpcLs.stream().map(e -> e.vpc).collect(Collectors.toList());
                        return new CmdResult(vpcLs, ls, utilJoinList(ls));
                    case L:
                        vpcLs = VpcHandle.list(targetResource);
                        ls = vpcLs.stream().map(VpcHandle.VpcEntry::toString).collect(Collectors.toList());
                        return new CmdResult(vpcLs, ls, utilJoinList(ls));
                    case a:
                        VpcHandle.add(this);
                        return new CmdResult();
                    case r:
                    case R:
                        VpcHandle.forceRemove(this);
                        return new CmdResult();
                }
            case iface:
                switch (action) {
                    case l:
                        int cnt = IfaceHandle.count(targetResource);
                        return new CmdResult(cnt, cnt, "" + cnt);
                    case L:
                        List<Iface> ifaces = IfaceHandle.list(targetResource);
                        List<Object> ls = ifaces.stream().map(Iface::toString).collect(Collectors.toList());
                        return new CmdResult(ifaces, ls, utilJoinList(ls));
                }
            case arp:
                switch (action) {
                    case l:
                        int cnt = ArpHandle.count(targetResource);
                        return new CmdResult(cnt, cnt, "" + cnt);
                    case L:
                        List<ArpHandle.ArpEntry> arpLs = ArpHandle.list(targetResource);
                        List<Object> ls = arpLs.stream().map(x -> x.toString(arpLs)).collect(Collectors.toList());
                        return new CmdResult(arpLs, ls, utilJoinList(ls));
                }
            case user:
                switch (action) {
                    case l:
                        List<String> users = UserHandle.names(targetResource);
                        return new CmdResult(users, users, utilJoinList(users));
                    case L:
                        List<UserHandle.UserInfo> userInfoList = UserHandle.list(targetResource);
                        List<String> strList = userInfoList.stream().map(UserHandle.UserInfo::toString).collect(Collectors.toList());
                        return new CmdResult(userInfoList, strList, utilJoinList(strList));
                    case a:
                        UserHandle.add(this);
                        return new CmdResult();
                    case r:
                    case R:
                        UserHandle.forceRemove(this);
                        return new CmdResult();
                }
            case tap:
                switch (action) {
                    case a:
                        String dev = TapHandle.add(this);
                        return new CmdResult(dev, dev, dev);
                    case r:
                    case R:
                        TapHandle.forceRemove(this);
                        return new CmdResult();
                }
            case ucli:
                switch (action) {
                    case a:
                        UserClientHandle.add(this);
                        return new CmdResult();
                    case r:
                    case R:
                        UserClientHandle.forceRemove(this);
                        return new CmdResult();
                }
            case ip:
                switch (action) {
                    case l:
                        var names = IpHandle.names(targetResource);
                        List<String> strNames = names.stream().map(IP::formatToIPString).collect(Collectors.toList());
                        return new CmdResult(names, strNames, utilJoinList(strNames));
                    case L:
                        Collection<IPMac> tuples = IpHandle.list(targetResource);
                        List<Object> strTuples = tuples.stream().map(o -> o.ip.formatToIPString() + " -> mac " + o.mac +
                            (o.annotations.isEmpty() ? "" : " annotations " + o.annotations)
                        ).collect(Collectors.toList());
                        return new CmdResult(tuples, strTuples, utilJoinList(strTuples));
                    case a:
                        IpHandle.add(this);
                        return new CmdResult();
                    case r:
                    case R:
                        IpHandle.forceRemove(this);
                        return new CmdResult();
                }
            case route:
                switch (action) {
                    case l:
                        var names = RouteHandle.names(targetResource);
                        return new CmdResult(names, names, utilJoinList(names));
                    case L:
                        var routes = RouteHandle.list(targetResource);
                        List<Object> strTuples = routes.stream().map(Object::toString).collect(Collectors.toList());
                        return new CmdResult(routes, strTuples, utilJoinList(strTuples));
                    case a:
                        RouteHandle.add(this);
                        return new CmdResult();
                    case r:
                    case R:
                        RouteHandle.forceRemove(this);
                        return new CmdResult();
                }
            case proxy:
                switch (action) {
                    case l:
                        var ipportls = ProxyHandle.names(targetResource);
                        return new CmdResult(ipportls, ipportls, utilJoinList(ipportls));
                    case L:
                        var entries = ProxyHandle.list(targetResource);
                        List<Object> strTuples = entries.stream().map(Object::toString).collect(Collectors.toList());
                        return new CmdResult(entries, strTuples, utilJoinList(strTuples));
                    case a:
                        ProxyHandle.add(this);
                        return new CmdResult();
                    case r:
                    case R:
                        ProxyHandle.forceRemove(this);
                        return new CmdResult();
                }
            case ck:
                switch (action) {
                    case l:
                    case L:
                        List<String> names = CertKeyHandle.names();
                        return new CmdResult(names, names, utilJoinList(names));
                    case r:
                        CertKeyHandle.preRemoveCheck(this);
                    case R:
                        CertKeyHandle.forceRemove(this);
                        return new CmdResult();
                    case a:
                        CertKeyHandle.add(this);
                        return new CmdResult();
                }
            default:
                throw new Exception("unknown resource type " + resource.type.fullname);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(action.fullname).append(" ").append(resource);
        if (preposition != null) {
            sb.append(" ").append(preposition).append(" ").append(prepositionResource);
        }
        for (Flag f : flags) {
            sb.append(" ").append(f.fullname);
        }
        for (Param p : args.keySet()) {
            String v = args.get(p);
            sb.append(" ").append(p.fullname).append(" ").append(v);
        }
        return sb.toString();
    }
}
