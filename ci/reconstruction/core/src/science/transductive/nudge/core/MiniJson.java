package science.transductive.nudge.core;

import java.util.*;

public final class MiniJson {
    private MiniJson() {}
    public static Object parse(String text){Parser p=new Parser(text);Object v=p.value();p.ws();if(!p.eof())throw new IllegalArgumentException("trailing JSON at "+p.i);return v;}
    public static String stringify(Object v){StringBuilder b=new StringBuilder();write(v,b);return b.toString();}
    private static void write(Object v,StringBuilder b){
        if(v==null){b.append("null");return;}
        if(v instanceof String){quote((String)v,b);return;}
        if(v instanceof Boolean||v instanceof Number){b.append(v);return;}
        if(v instanceof Map){Map<?,?>m=(Map<?,?>)v;b.append('{');List<String>keys=new ArrayList<String>();for(Object k:m.keySet())keys.add((String)k);Collections.sort(keys);boolean first=true;for(String k:keys){if(!first)b.append(',');first=false;quote(k,b);b.append(':');write(m.get(k),b);}b.append('}');return;}
        if(v instanceof Iterable){b.append('[');boolean first=true;for(Object x:(Iterable<?>)v){if(!first)b.append(',');first=false;write(x,b);}b.append(']');return;}
        throw new IllegalArgumentException("unsupported JSON type "+v.getClass());
    }
    private static void quote(String s,StringBuilder b){b.append('"');for(char c:s.toCharArray()){switch(c){case '"':b.append("\\\"");break;case '\\':b.append("\\\\");break;case '\b':b.append("\\b");break;case '\f':b.append("\\f");break;case '\n':b.append("\\n");break;case '\r':b.append("\\r");break;case '\t':b.append("\\t");break;default:if(c<0x20)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}b.append('"');}
    private static final class Parser{
        final String s;int i=0;Parser(String s){this.s=Objects.requireNonNull(s);}boolean eof(){return i>=s.length();}void ws(){while(!eof()&&Character.isWhitespace(s.charAt(i)))i++;}
        Object value(){ws();if(eof())throw err("expected value");char c=s.charAt(i);if(c=='{')return object();if(c=='[')return array();if(c=='"')return string();if(c=='t')return lit("true",Boolean.TRUE);if(c=='f')return lit("false",Boolean.FALSE);if(c=='n')return lit("null",null);return number();}
        Map<String,Object> object(){i++;ws();Map<String,Object>m=new LinkedHashMap<String,Object>();if(peek('}')){i++;return m;}while(true){ws();if(!peek('"'))throw err("expected key");String k=string();ws();expect(':');Object v=value();m.put(k,v);ws();if(peek('}')){i++;return m;}expect(',');}}
        List<Object> array(){i++;ws();List<Object>a=new ArrayList<Object>();if(peek(']')){i++;return a;}while(true){a.add(value());ws();if(peek(']')){i++;return a;}expect(',');}}
        String string(){expect('"');StringBuilder b=new StringBuilder();while(!eof()){char c=s.charAt(i++);if(c=='"')return b.toString();if(c!='\\'){b.append(c);continue;}if(eof())throw err("bad escape");char e=s.charAt(i++);switch(e){case '"':b.append('"');break;case '\\':b.append('\\');break;case '/':b.append('/');break;case 'b':b.append('\b');break;case 'f':b.append('\f');break;case 'n':b.append('\n');break;case 'r':b.append('\r');break;case 't':b.append('\t');break;case 'u':if(i+4>s.length())throw err("bad unicode");b.append((char)Integer.parseInt(s.substring(i,i+4),16));i+=4;break;default:throw err("bad escape");}}throw err("unterminated string");}
        Object number(){int st=i;if(peek('-'))i++;while(!eof()&&Character.isDigit(s.charAt(i)))i++;boolean fp=false;if(peek('.')){fp=true;i++;while(!eof()&&Character.isDigit(s.charAt(i)))i++;}if(!eof()&&(s.charAt(i)=='e'||s.charAt(i)=='E')){fp=true;i++;if(!eof()&&(s.charAt(i)=='+'||s.charAt(i)=='-'))i++;while(!eof()&&Character.isDigit(s.charAt(i)))i++;}if(st==i)throw err("bad number");String n=s.substring(st,i);return fp?Double.valueOf(n):Long.valueOf(n);}
        Object lit(String x,Object v){if(!s.startsWith(x,i))throw err("bad literal");i+=x.length();return v;}boolean peek(char c){return !eof()&&s.charAt(i)==c;}void expect(char c){ws();if(eof()||s.charAt(i)!=c)throw err("expected "+c);i++;}IllegalArgumentException err(String x){return new IllegalArgumentException(x+" at "+i);}
    }
}
