package com.github.sviperll.staticmustache.examples;

import com.github.sviperll.staticmustache.GenerateRenderableAdapter;

@GenerateRenderableAdapter(template="context-example.mustache")
public record ContextExample(String a, String b, Sec sec, C c ) {

    public record Sec(String b) {}
    
    public record C(String d) {}
    
    //{"a":"foo","b":"wrong","sec":{"b":"bar"},"c":{"d":"baz"}}
    public static ContextExample forTest() {
        Sec sec = new Sec("bar");
        C c = new C("baz");
        return new ContextExample("foo", "wrong", sec, c);
    }
}
