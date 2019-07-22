package vproxy.processor.http1;

import vproxy.processor.OOContext;
import vproxy.util.Utils;

import java.net.InetSocketAddress;

public class HttpContext extends OOContext<HttpSubContext> {
    final String clientAddress;
    final String clientPort;

    int currentBackend = -1;

    public HttpContext(InetSocketAddress clientSock) {
        clientAddress = clientSock == null ? null : Utils.ipStr(clientSock.getAddress().getAddress());
        clientPort = clientSock == null ? null : "" + clientSock.getPort();
    }

    @Override
    public int connection(HttpSubContext front) {
        if (front.isIdle()) {
            int foo = currentBackend;
            currentBackend = -1;
            return foo;
        }
        return currentBackend;
    }

    @Override
    public void chosen(HttpSubContext front, HttpSubContext subCtx) {
        currentBackend = subCtx.connId;
    }
}
