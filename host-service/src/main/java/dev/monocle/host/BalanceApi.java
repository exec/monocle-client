package dev.monocle.host;

import com.google.gson.*;
import java.net.URI;
import java.util.*;
import static dev.monocle.coordinator.TaskWire.text;

/** Isolated from operator resources: balance keys never reach status, jobs, players, or host control. */
final class BalanceApi {
    static boolean route(URI uri){String p=uri.getPath();return p.equals("/v1/balances")||p.startsWith("/v1/balances/")||p.equals("/v1/balance-api-keys")||p.startsWith("/v1/balance-api-keys/");}
    static ResourceApi.Reply handle(CreditBank bank,String method,URI uri,JsonObject body,String requestId,boolean admin,String actor){
        if(!uri.getRawPath().equals(uri.getPath())||uri.getRawQuery()!=null)throw new IllegalArgumentException("Encoded paths and queries are not supported for bank resources");
        String[] p=uri.getPath().split("/",-1);boolean keys=p[2].equals("balance-api-keys");
        if(keys&&!admin)throw new IllegalArgumentException("Administrator API key required");
        if(method.equals("GET")){
            if(p.length==3)return new ResourceApi.Reply(200,keys?bank.keys():bank.accounts());
            if(!keys&&p.length==4)return new ResourceApi.Reply(200,bank.account(p[3]));
            if(!keys&&p.length==5&&p[4].equals("transactions"))return new ResourceApi.Reply(200,bank.history(p[3]));
        }else{
            UUID.fromString(requestId);
            if(keys&&p.length==3&&method.equals("POST")){
                if(!body.keySet().equals(Set.of("name","scopes"))||!body.get("name").isJsonPrimitive()||!body.getAsJsonPrimitive("name").isString()||!body.get("scopes").isJsonArray())throw new IllegalArgumentException("Supply name and scopes");
                Set<String> scopes=new HashSet<>();for(JsonElement scope:body.getAsJsonArray("scopes")){if(!scope.isJsonPrimitive()||!scope.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Invalid balance API scope");scopes.add(scope.getAsString());}
                return new ResourceApi.Reply(200,bank.createKey(requestId,text(body,"name"),scopes));
            }
            if(keys&&p.length==4&&method.equals("DELETE")&&body.isEmpty())return new ResourceApi.Reply(200,bank.revokeKey(p[3]));
            if(!keys&&p.length==5&&p[4].equals("transactions")&&method.equals("POST")){
                if(!body.keySet().equals(Set.of("name","direction","amount","reason")))throw new IllegalArgumentException("Supply name, direction, amount and reason");
                for(String field:List.of("name","direction","reason"))if(!body.get(field).isJsonPrimitive()||!body.getAsJsonPrimitive(field).isString())throw new IllegalArgumentException("Expected text "+field);
                String direction=text(body,"direction");if(!Set.of("credit","debit").contains(direction))throw new IllegalArgumentException("direction must be credit or debit");
                long amount=CreditBank.amount(body,"amount",CreditBank.MAX_ADJUSTMENT);
                return new ResourceApi.Reply(200,bank.adjust(requestId,p[3],text(body,"name"),direction.equals("credit")?amount:-amount,text(body,"reason"),actor));
            }
        }
        JsonObject missing=new JsonObject();missing.addProperty("error","Unknown bank resource or method");return new ResourceApi.Reply(404,missing);
    }
}
