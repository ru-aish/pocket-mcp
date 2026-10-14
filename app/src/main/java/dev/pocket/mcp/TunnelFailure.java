package dev.pocket.mcp;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/** Only fixed classifications and ngrok's numeric error codes may leave the SDK boundary. */
public final class TunnelFailure {
    private static final Pattern CODE=Pattern.compile("\\bERR_NGROK_[0-9]{1,8}\\b");
    public final String code;
    public final String kind;
    private TunnelFailure(String code,String kind) {this.code=code;this.kind=kind;}
    public static TunnelFailure from(Throwable error) {
        String code="",kind="unknown";
        for(int depth=0;error!=null && depth<12;depth++,error=error.getCause()) {
            String message=error.getMessage();
            if(message!=null) {
                Matcher match=CODE.matcher(message);
                if(code.isEmpty() && match.find()) code=match.group();
                String lower=message.toLowerCase(Locale.ROOT);
                if(lower.contains("failed to establish tcp connection")) kind="network-connect";
                if(lower.contains("failed to lookup address") || lower.contains("dns error")) kind="dns";
            }
            if(error instanceof UnknownHostException) kind="dns";
            else if(error instanceof SocketTimeoutException) kind="timeout";
            else if(error instanceof SSLException) kind="tls";
            else if(error instanceof NoSuchMethodError || error instanceof NoSuchFieldError || error instanceof ClassNotFoundException) kind="sdk-class-mismatch";
            else if(error instanceof LinkageError) kind="native-linkage";
            else if(error instanceof java.io.IOException && kind.equals("unknown")) kind="io";
        }
        return new TunnelFailure(code,kind);
    }
    public String summary() {return "Diagnostic: "+kind+(code.isEmpty()?"":" · "+code+" · https://ngrok.com/docs/errors/"+code.toLowerCase(Locale.ROOT));}
    public String advice() {
        return switch(code) {
            case "ERR_NGROK_105","ERR_NGROK_107" -> "ngrok rejected the authtoken. Save the authtoken from your ngrok dashboard; it is different from the MCP bearer token.";
            case "ERR_NGROK_108" -> "Your ngrok account's agent-session limit was reached. Stop an unused ngrok agent in your account, then retry.";
            case "ERR_NGROK_334" -> "This ngrok domain is already in use. Stop the other endpoint or choose a different reserved domain, then retry.";
            default -> "";
        };
    }
}
