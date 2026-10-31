package dev.monocle.coordinator;

import com.google.gson.*;
import static dev.monocle.coordinator.TaskWire.text;

/** Credit settlement uses confirmed drops, never a guessed inventory delta. */
public final class DeliveryReceipt {
    private DeliveryReceipt(){}
    public static JsonObject checkpoint(JsonObject run){
        if(!run.has("package")||!text(run.getAsJsonObject("package"),"entry").equals("task-kit-delivery")||!run.has("args")||!text(run.getAsJsonObject("args"),"destination").equals("Player"))return null;
        int expected=StashCatalog.integer(run.getAsJsonObject("args"),"count",1,1000000),delivered=0;boolean uncertain=false,timedOut=false;
        if(run.has("workflowResult")&&run.getAsJsonObject("workflowResult").has("delivered"))delivered=StashCatalog.integer(run.getAsJsonObject("workflowResult"),"delivered",0,expected);
        else if(run.has("stack")&&!run.getAsJsonArray("stack").isEmpty()){
            JsonObject frame=run.getAsJsonArray("stack").get(0).getAsJsonObject(),state=frame.getAsJsonObject("state");
            timedOut=state!=null&&dev.monocle.coordinator.TaskWire.flag(state,"tpaTimedOut");
            if(frame.has("result"))timedOut|=dev.monocle.coordinator.TaskWire.flag(frame.getAsJsonObject("result"),"tpaTimedOut");
            if(frame.has("native")&&frame.getAsJsonObject("native").has("result"))timedOut|=dev.monocle.coordinator.TaskWire.flag(frame.getAsJsonObject("native").getAsJsonObject("result"),"tpaTimedOut");
            if(state!=null&&state.has("total"))delivered=StashCatalog.integer(state,"total",0,expected);
            if(state!=null&&text(state,"phase").equals("dropping")){
                int batch=StashCatalog.integer(state,"batch",0,expected);
                if(frame.has("native")){JsonObject nativeState=frame.getAsJsonObject("native");delivered+=batch-StashCatalog.integer(nativeState,"remaining",0,batch);uncertain=nativeState.has("pendingDrop");}
                else if(frame.has("result")&&frame.getAsJsonObject("result").has("dropped"))delivered+=StashCatalog.integer(frame.getAsJsonObject("result"),"dropped",0,batch);
            }
        }else uncertain=true;
        JsonObject receipt=new JsonObject();receipt.addProperty("expected",expected);receipt.addProperty("delivered",delivered);receipt.addProperty("uncertain",uncertain);if(timedOut)receipt.addProperty("failureReason","tpa_timeout");return checked(receipt,expected);
    }
    public static JsonObject checked(JsonObject receipt,int expected){
        if(!receipt.keySet().containsAll(java.util.Set.of("expected","delivered","uncertain"))||!java.util.Set.of("expected","delivered","uncertain","failureReason").containsAll(receipt.keySet())||StashCatalog.integer(receipt,"expected",1,1000000)!=expected||!receipt.get("uncertain").isJsonPrimitive()||!receipt.getAsJsonPrimitive("uncertain").isBoolean())throw new IllegalArgumentException("Invalid delivery credit receipt");
        int delivered=StashCatalog.integer(receipt,"delivered",0,expected);
        if(receipt.has("failureReason")&&(!receipt.get("failureReason").isJsonPrimitive()||!receipt.getAsJsonPrimitive("failureReason").isString()||!text(receipt,"failureReason").equals("tpa_timeout")||delivered!=0||receipt.get("uncertain").getAsBoolean()))throw new IllegalArgumentException("Invalid delivery timeout evidence");
        return receipt.deepCopy();
    }
}
