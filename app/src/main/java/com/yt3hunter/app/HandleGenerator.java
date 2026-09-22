package com.yt3hunter.app;

import java.util.*;

public final class HandleGenerator {
    private static final String ALNUM="abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String MID=ALNUM+"_-.·";

    private static final Set<String> COMMON=new HashSet<>(Arrays.asList(
        "abc","xyz","qwe","asd","zxc","lol","omg","wtf","pro","vip","top","one","you","ytb","app","web","www",
        "cat","dog","boy","man","max","neo","ace","sky","red","ice","sun","win","fun","wow","yes","usa","rus","kor",
        "fox","god","xxx","owo","uwu","ttv"
    ));

    private static final Set<String> KEYBOARD=new HashSet<>(Arrays.asList(
        "qwe","wer","ert","rty","tyu","yui","uio","iop",
        "asd","sdf","dfg","fgh","ghj","hjk","jkl",
        "zxc","xcv","cvb","vbn","bnm"
    ));

    private static final Set<String> SEQ=new HashSet<>();

    static {
        for(String s:new String[]{"abcdefghijklmnopqrstuvwxyz","0123456789"}){
            for(int i=0;i<s.length()-2;i++){
                String q=s.substring(i,i+3);
                SEQ.add(q);
                SEQ.add(new StringBuilder(q).reverse().toString());
            }
        }
    }

    public static List<String> build() {
        ArrayList<String> out=new ArrayList<>(52000);

        for(int i=0;i<ALNUM.length();i++){
            for(int j=0;j<MID.length();j++){
                for(int k=0;k<ALNUM.length();k++){
                    String h=""+ALNUM.charAt(i)+MID.charAt(j)+ALNUM.charAt(k);
                    if(!pretty(h)) out.add(h);
                }
            }
        }

        out.sort((a,b)->Double.compare(score(b),score(a)));
        return out;
    }

    private static boolean pretty(String h) {
        char a=h.charAt(0), b=h.charAt(1), c=h.charAt(2);

        if(a==b || b==c || a==c) return true;
        if(SEQ.contains(h) || KEYBOARD.contains(h) || COMMON.contains(h)) return true;
        if(h.matches("\\d{3}")) return true;
        if(h.matches(".*(69|420|007|666|777|888|999|911|123|321).*")) return true;
        if(h.matches(".*(00|11|22|33|44|55|66|77|88|99).*")) return true;

        return false;
    }

    private static double score(String h) {
        double s=0;

        if("_-.·".indexOf(h.charAt(1))>=0) s+=100;
        if(h.matches(".*[a-z].*") && h.matches(".*\\d.*")) s+=30;

        for(int i=0;i<3;i++){
            char c=h.charAt(i);
            if("qzxjvkw".indexOf(c)>=0) s+=7;
            if("aeiou".indexOf(c)>=0) s-=2;
        }

        long x=2166136261L;
        for(int i=0;i<h.length();i++){
            x^=h.charAt(i);
            x=(x*16777619L)&0xffffffffL;
        }

        return s+(x%10000)/100000.0;
    }

    private HandleGenerator(){}
}
