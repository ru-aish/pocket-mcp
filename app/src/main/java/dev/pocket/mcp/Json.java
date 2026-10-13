package dev.pocket.mcp;

import org.json.JSONArray;
import org.json.JSONObject;

final class Json {
    static JSONObject obj(Object... values) {
        JSONObject result=new JSONObject();
        try { for(int i=0;i<values.length;i+=2) result.put((String)values[i],values[i+1]==null?JSONObject.NULL:values[i+1]); }
        catch(org.json.JSONException e) {throw new IllegalArgumentException(e);}
        return result;
    }
    static JSONArray arr(Object... values) {JSONArray a=new JSONArray();for(Object v:values) a.put(v);return a;}
    static JSONObject tool(JSONObject data) {return obj("content",arr(obj("type","text","text",data.toString())),"structuredContent",data,"isError",false);}
    static JSONObject failure(String code,String message) {JSONObject data=obj("code",code,"message",message,"retry_safe",false);return obj("content",arr(obj("type","text","text",data.toString())),"structuredContent",data,"isError",true);}
}
