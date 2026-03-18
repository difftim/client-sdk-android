/// ////////////////////////////////////////////////////////////////////////////
// file : Config.java
// author : antoniozhou
/// ////////////////////////////////////////////////////////////////////////////

package org.difft.android.smp;


public class Config {
    public interface LogHandler {
        public void log(int level, String msg);
    }

    /**
     * Certificate verification callback for QUIC/TLS connections.
     * Called during the TLS handshake with the server's certificate chain.
     */
    public interface CertVerifier {
        /**
         * @param certs DER-encoded X509 certificate chain from the server.
         *              certs[0] is the leaf certificate, followed by intermediates.
         * @param hostname the server hostname to verify against the certificate.
         * @return true if verification passes, false to reject the connection.
         */
        boolean verify(byte[][] certs, String hostname);
    }

    // Connector use config properties
    public String hostname = "localhost";
    // Server use config properties
    public int port = 8003;
    public int backlog = 1000;
    public boolean reusePort = false;
    public boolean ssl = false;
    public String privateKeyFile = "";
    public String certificateFile = "";
    // Server/Connector both use the same config properties
    public int taskThreads = 16;
    public int timerThreads = 4;
    // connection idle time out in milliseconds
    public int idleTimeOut = 20000;
    public String alpn = "ttsignal";
    public int maxConnections = 1000;
    public int congestCtrl = 'B';
    // ping on switch
    public boolean pingOn = false;
    // ping interval, in milliseconds
    public int pingInterval = 10000;
    public String logFile = "";
    public LogHandler logHandler = null;
    // Log level, error : E, info : I, debug : D, trace : T, warn : W
    public int logLevel = 0;
    // Certificate verifier for TLS connections; null means no verification (insecure)
    public CertVerifier certVerifier = null;

    public Config() {
        // Default constructor
    }
}
