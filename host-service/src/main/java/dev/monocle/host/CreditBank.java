package dev.monocle.host;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.sql.*;
import java.util.*;
import static dev.monocle.coordinator.TaskWire.text;

/** Host-wide UUID accounts. Reads never create accounts; each mutation and receipt commit together. */
final class CreditBank implements AutoCloseable {
    static final long MAX_BALANCE=1_000_000_000L,MAX_ADJUSTMENT=1_000_000L;
    private final Connection db;
    CreditBank(Path root){
        Connection opened=null;
        try{
            Files.createDirectories(root);Path file=root.resolve("kit-credits.sqlite");
            db=opened=DriverManager.getConnection("jdbc:sqlite:"+file);
            try(var s=db.createStatement()){
                s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");
                int version;try(var r=s.executeQuery("PRAGMA user_version")){r.next();version=r.getInt(1);}
                if(version!=0&&version!=1)throw new IllegalStateException("Unsupported kit credit database version");
                s.execute("CREATE TABLE IF NOT EXISTS accounts(player TEXT PRIMARY KEY,name TEXT NOT NULL,balance INTEGER NOT NULL CHECK(balance BETWEEN 0 AND 1000000000),updated INTEGER NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS transactions(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL UNIQUE,player TEXT NOT NULL REFERENCES accounts(player),delta INTEGER NOT NULL,balance_after INTEGER NOT NULL,reason TEXT NOT NULL,actor TEXT NOT NULL,at INTEGER NOT NULL,fingerprint TEXT NOT NULL,response TEXT NOT NULL)");
                s.execute("CREATE INDEX IF NOT EXISTS transactions_player ON transactions(player,seq)");
                s.execute("CREATE TABLE IF NOT EXISTS order_settlements(id TEXT PRIMARY KEY,player TEXT NOT NULL,value TEXT NOT NULL CHECK(json_valid(value)),at INTEGER NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS api_keys(id TEXT PRIMARY KEY,name TEXT NOT NULL,digest TEXT NOT NULL UNIQUE,can_read INTEGER NOT NULL,can_write INTEGER NOT NULL,created INTEGER NOT NULL,revoked INTEGER)");
                s.execute("CREATE TABLE IF NOT EXISTS kitbot_state(id INTEGER PRIMARY KEY CHECK(id=1),value TEXT NOT NULL CHECK(json_valid(value)))");s.execute("PRAGMA user_version=1");s.execute("PRAGMA foreign_keys=ON");
            }
            if(file.getFileSystem().supportedFileAttributeViews().contains("posix"))for(String suffix:List.of("","-wal","-shm")){Path privateFile=root.resolve("kit-credits.sqlite"+suffix);if(Files.exists(privateFile))Files.setPosixFilePermissions(privateFile,PosixFilePermissions.fromString("rw-------"));}
        }catch(Exception e){if(opened!=null)try{opened.close();}catch(SQLException close){e.addSuppressed(close);}throw new IllegalStateException("Kit credit database unavailable",e);}
    }
    static String player(String id){String normalized=UUID.fromString(id).toString();if(!normalized.equals(id))throw new IllegalArgumentException("Use a canonical player UUID");return normalized;}
    static String name(String name){if(!name.matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("Invalid player username");return name;}
    static long amount(JsonObject body,String key,long max){
        if(!body.has(key)||!body.get(key).isJsonPrimitive()||!body.getAsJsonPrimitive(key).isNumber())throw new IllegalArgumentException("Expected whole "+key);
        long n;try{n=body.get(key).getAsBigDecimal().longValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException("Expected whole "+key);}
        if(n<1||n>max)throw new IllegalArgumentException(key+" must be 1–"+max);return n;
    }
    synchronized JsonObject account(String id){
        player(id);JsonObject out=new JsonObject();out.addProperty("playerId",id);out.addProperty("exists",false);out.addProperty("balance",0);
        try(var s=db.prepareStatement("SELECT name,balance,updated FROM accounts WHERE player=?")){
            s.setString(1,id);try(var r=s.executeQuery()){if(r.next()){out.addProperty("exists",true);out.addProperty("name",r.getString(1));out.addProperty("balance",r.getLong(2));out.addProperty("updatedAt",r.getLong(3));}}return out;
        }catch(SQLException e){throw failure(e);}
    }
    synchronized JsonObject accounts(){
        JsonArray rows=new JsonArray();try(var s=db.createStatement();var r=s.executeQuery("SELECT player FROM accounts ORDER BY updated DESC LIMIT 200")){while(r.next())rows.add(account(r.getString(1)));}
        catch(SQLException e){throw failure(e);}JsonObject out=new JsonObject();out.add("items",rows);return out;
    }
    synchronized JsonObject history(String id){
        player(id);JsonArray rows=new JsonArray();
        try(var s=db.prepareStatement("SELECT response FROM transactions WHERE player=? ORDER BY seq DESC LIMIT 100")){s.setString(1,id);try(var r=s.executeQuery()){while(r.next())rows.add(JsonParser.parseString(r.getString(1)));}}
        catch(SQLException e){throw failure(e);}JsonObject out=new JsonObject();out.add("items",rows);JsonArray settlements=new JsonArray();
        try(var s=db.prepareStatement("SELECT value FROM order_settlements WHERE player=? ORDER BY at DESC LIMIT 100")){s.setString(1,id);try(var r=s.executeQuery()){while(r.next())settlements.add(JsonParser.parseString(r.getString(1)));}}
        catch(SQLException e){throw failure(e);}out.add("settlements",settlements);return out;
    }
    private interface Write { JsonObject run() throws SQLException; }
    private JsonObject transaction(Write operation){
        try{db.setAutoCommit(false);try{JsonObject out=operation.run();db.commit();return out;}
            catch(SQLException|RuntimeException e){db.rollback();throw e;}finally{db.setAutoCommit(true);}}
        catch(SQLException e){throw failure(e);}
    }
    synchronized JsonObject adjust(String requestId,String id,String username,long delta,String reason,String actor){
        return transaction(()->adjustment(requestId,id,username,delta,reason,actor));
    }
    private JsonObject adjustment(String requestId,String id,String username,long delta,String reason,String actor)throws SQLException{
        UUID.fromString(requestId);player(id);name(username);
        if(delta==0||delta>MAX_ADJUSTMENT||delta< -MAX_ADJUSTMENT)throw new IllegalArgumentException("Credit adjustment is out of range");
        if(reason.isBlank()||reason.length()>256||reason.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Supply a reason of 1–256 printable characters");
        if(actor.isBlank()||actor.length()>128)throw new IllegalArgumentException("Invalid credit actor");
        String fingerprint=dev.monocle.coordinator.TaskFiles.hash(id+"\n"+username+"\n"+delta+"\n"+reason+"\n"+actor);
        try(var s=db.prepareStatement("SELECT fingerprint,response FROM transactions WHERE id=?")){s.setString(1,requestId);try(var r=s.executeQuery()){if(r.next()){if(!r.getString(1).equals(fingerprint))throw new IllegalStateException("Idempotency key already belongs to a different credit adjustment");JsonObject prior=JsonParser.parseString(r.getString(2)).getAsJsonObject();return prior;}}}
        JsonObject before=account(id);long balance=before.get("balance").getAsLong()+delta;
        if(balance<0)throw new IllegalStateException("Insufficient kit credits");
        if(balance>MAX_BALANCE)throw new IllegalStateException("Kit credit balance limit exceeded");
        long now=System.currentTimeMillis();
        try(var s=db.prepareStatement("INSERT INTO accounts(player,name,balance,updated) VALUES(?,?,?,?) ON CONFLICT(player) DO UPDATE SET name=excluded.name,balance=excluded.balance,updated=excluded.updated")){s.setString(1,id);s.setString(2,username);s.setLong(3,balance);s.setLong(4,now);s.executeUpdate();}
        JsonObject out=account(id);out.addProperty("transactionId",requestId);out.addProperty("delta",delta);out.addProperty("reason",reason);out.addProperty("actor",actor);
        try(var s=db.prepareStatement("INSERT INTO transactions(id,player,delta,balance_after,reason,actor,at,fingerprint,response) VALUES(?,?,?,?,?,?,?,?,?)")){s.setString(1,requestId);s.setString(2,id);s.setLong(3,delta);s.setLong(4,balance);s.setString(5,reason);s.setString(6,actor);s.setLong(7,now);s.setString(8,fingerprint);s.setString(9,out.toString());s.executeUpdate();}
        return out;
    }
    synchronized JsonObject keys(){
        JsonArray rows=new JsonArray();try(var s=db.createStatement();var r=s.executeQuery("SELECT id,name,can_read,can_write,created,revoked FROM api_keys ORDER BY created DESC")){while(r.next())rows.add(key(r));}
        catch(SQLException e){throw failure(e);}JsonObject out=new JsonObject();out.add("items",rows);return out;
    }
    synchronized JsonObject kitbot(){
        try(var s=db.createStatement();var r=s.executeQuery("SELECT value FROM kitbot_state WHERE id=1")){return r.next()?JsonParser.parseString(r.getString(1)).getAsJsonObject():new JsonObject();}
        catch(SQLException e){throw failure(e);}
    }
    synchronized void saveKitbot(JsonObject next,JsonObject order,long refund){
        transaction(()->{
            if(order!=null){
                String id=text(order,"id"),player=text(order,"requester"),username=text(order,"name");
                if(refund<0)adjustment(id,player,username,-order.get("count").getAsLong(),"Kit delivery "+id,"kitbot");
                else if(refund>0)adjustment(UUID.nameUUIDFromBytes(("kit-refund:"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),player,username,refund,order.has("creditSettlementReason")?"Reviewed refund "+id+": "+text(order,"creditSettlementReason"):"Undelivered kit refund "+id,order.has("creditSettlementActor")?text(order,"creditSettlementActor"):"kitbot");
                if(text(order,"creditSettlement").equals("Settled")){
                    JsonObject settlement=new JsonObject();settlement.addProperty("orderId",id);settlement.addProperty("playerId",player);settlement.addProperty("delivered",order.get("count").getAsLong()-refund);settlement.addProperty("refunded",refund);settlement.addProperty("actor",order.has("creditSettlementActor")?text(order,"creditSettlementActor"):"kitbot");settlement.addProperty("reason",order.has("creditSettlementReason")?text(order,"creditSettlementReason"):"Confirmed delivery settlement");long at=System.currentTimeMillis();settlement.addProperty("at",at);
                    try(var s=db.prepareStatement("INSERT INTO order_settlements VALUES(?,?,?,?) ON CONFLICT(id) DO NOTHING")){s.setString(1,id);s.setString(2,player);s.setString(3,settlement.toString());s.setLong(4,at);s.executeUpdate();}
                }
            }
            try(var s=db.prepareStatement("INSERT INTO kitbot_state VALUES(1,?) ON CONFLICT(id) DO UPDATE SET value=excluded.value")){s.setString(1,next.toString());s.executeUpdate();}return new JsonObject();
        });
    }
    private static JsonObject key(ResultSet r)throws SQLException{
        JsonObject out=new JsonObject();out.addProperty("id",r.getString(1));out.addProperty("name",r.getString(2));JsonArray scopes=new JsonArray();if(r.getBoolean(3))scopes.add("balances:read");if(r.getBoolean(4))scopes.add("balances:write");out.add("scopes",scopes);out.addProperty("createdAt",r.getLong(5));out.addProperty("revoked",r.getObject(6)!=null);return out;
    }
    synchronized JsonObject createKey(String id,String label,Set<String> scopes){
        UUID.fromString(id);if(label.isBlank()||label.length()>64||label.chars().anyMatch(Character::isISOControl)||scopes.isEmpty()||!Set.of("balances:read","balances:write").containsAll(scopes))throw new IllegalArgumentException("Choose a name and balance read/write permissions");
        try{
            try(var s=db.prepareStatement("SELECT id,name,can_read,can_write,created,revoked FROM api_keys WHERE id=?")){s.setString(1,id);try(var r=s.executeQuery()){if(r.next()){JsonObject prior=key(r);Set<String> old=new HashSet<>();for(JsonElement scope:prior.getAsJsonArray("scopes"))old.add(scope.getAsString());if(!text(prior,"name").equals(label)||!old.equals(scopes))throw new IllegalStateException("Idempotency key already belongs to another API key");return prior;}}}
            try(var s=db.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM api_keys WHERE revoked IS NULL")){r.next();if(r.getInt(1)>=128)throw new IllegalStateException("Revoke an unused balance API key first (128 active-key limit)");}
            byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token="mcb_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);long now=System.currentTimeMillis();
            try(var s=db.prepareStatement("INSERT INTO api_keys VALUES(?,?,?,?,?,?,NULL)")){s.setString(1,id);s.setString(2,label);s.setString(3,hash(token));s.setBoolean(4,scopes.contains("balances:read"));s.setBoolean(5,scopes.contains("balances:write"));s.setLong(6,now);s.executeUpdate();}
            JsonObject out=new JsonObject();out.addProperty("id",id);out.addProperty("name",label);out.add("scopes",new Gson().toJsonTree(scopes));out.addProperty("createdAt",now);out.addProperty("revoked",false);out.addProperty("token",token);return out;
        }catch(SQLException e){throw failure(e);}
    }
    synchronized JsonObject revokeKey(String id){
        UUID.fromString(id);try(var s=db.prepareStatement("UPDATE api_keys SET revoked=COALESCE(revoked,?) WHERE id=?")){s.setLong(1,System.currentTimeMillis());s.setString(2,id);if(s.executeUpdate()!=1)throw new IllegalArgumentException("Unknown balance API key");}
        catch(SQLException e){throw failure(e);}JsonObject out=new JsonObject();out.addProperty("id",id);out.addProperty("revoked",true);return out;
    }
    synchronized String authorize(String authorization,String scope){
        if(authorization==null||!authorization.startsWith("Bearer mcb_")||authorization.length()>128)return null;
        try(var s=db.prepareStatement("SELECT id,can_read,can_write FROM api_keys WHERE digest=? AND revoked IS NULL")){s.setString(1,hash(authorization.substring(7)));try(var r=s.executeQuery()){if(r.next()&&r.getBoolean(scope.equals("balances:read")?2:3))return r.getString(1);}}
        catch(SQLException e){throw failure(e);}return null;
    }
    private static String hash(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static IllegalStateException failure(SQLException e){return new IllegalStateException("Credit operation was not acknowledged; retry with the same idempotency key",e);}
    public synchronized void close(){try{db.close();}catch(SQLException e){throw failure(e);}}
}
