package dev.monocle.coordinator;

import com.google.gson.*;
import java.util.*;

/** Edits the groups/settings envelope only. Nested SNBT values remain opaque and byte-for-byte intact. */
public final class SettingsOverlay {
    private SettingsOverlay() {}
    public static String merge(String original, String patch) {
        Map<String,String> root=compound(original), incoming=compound(patch);
        List<String> groups=list(root.getOrDefault("groups","[]"));
        for(String item:list(incoming.getOrDefault("groups","[]"))) {
            Map<String,String> group=compound(item);String name=name(group);int index=find(groups,name);
            if(index<0){groups.add(item);continue;}
            Map<String,String> existing=compound(groups.get(index));List<String> settings=list(existing.getOrDefault("settings","[]"));
            for(String value:list(group.getOrDefault("settings","[]"))) {
                int setting=find(settings,name(compound(value)));if(setting<0)settings.add(value);else settings.set(setting,value);
            }
            existing.put("settings","["+String.join(",",settings)+"]");groups.set(index,encode(existing));
        }
        root.put("groups","["+String.join(",",groups)+"]");return encode(root);
    }
    private static int find(List<String> entries,String name){int found=-1;for(int i=0;i<entries.size();i++)if(name(compound(entries.get(i))).equals(name)){if(found>=0)throw new IllegalArgumentException("Duplicate settings name");found=i;}return found;}
    private static String name(Map<String,String> object){if(!object.containsKey("name"))throw new IllegalArgumentException("Missing settings name");return string(object.get("name"));}
    private static String string(String token){JsonElement value=JsonParser.parseString(token);if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Expected settings name");return value.getAsString();}
    private static Map<String,String> compound(String value){
        value=value.strip();if(!value.startsWith("{")||!value.endsWith("}"))throw new IllegalArgumentException("Expected SNBT compound");
        Map<String,String> result=new LinkedHashMap<>();
        for(String field:split(value.substring(1,value.length()-1),',')) {
            List<String> pair=split(field,':');if(pair.size()!=2)throw new IllegalArgumentException("Invalid settings field");
            if(result.put(string(pair.get(0)),pair.get(1))!=null)throw new IllegalArgumentException("Duplicate settings field");
        }
        return result;
    }
    private static List<String> list(String value){value=value.strip();if(!value.startsWith("[")||!value.endsWith("]"))throw new IllegalArgumentException("Expected settings list");return split(value.substring(1,value.length()-1),',');}
    private static String encode(Map<String,String> fields){List<String> parts=new ArrayList<>();fields.forEach((key,value)->parts.add(new Gson().toJson(key)+":"+value));return "{"+String.join(",",parts)+"}";}
    private static List<String> split(String text,char delimiter){
        if(text.length()>524288)throw new IllegalArgumentException("Settings too large");
        List<String> result=new ArrayList<>();Deque<Character> nesting=new ArrayDeque<>();char quote=0;boolean escaped=false;int start=0;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(quote!=0){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c==quote)quote=0;continue;}
            if(c=='\''||c=='"'){quote=c;continue;}
            if(c=='{'||c=='['){if(nesting.size()>=64)throw new IllegalArgumentException("Settings too deeply nested");nesting.push(c);}
            else if(c=='}'||c==']'){if(nesting.isEmpty()||nesting.pop()!=(c=='}'?'{':'['))throw new IllegalArgumentException("Unbalanced settings");}
            else if(c==delimiter&&nesting.isEmpty()){String part=text.substring(start,i).strip();if(part.isEmpty())throw new IllegalArgumentException("Empty settings entry");result.add(part);start=i+1;}
        }
        if(quote!=0||!nesting.isEmpty())throw new IllegalArgumentException("Unterminated settings");
        String last=text.substring(start).strip();if(!last.isEmpty())result.add(last);return result;
    }
}
