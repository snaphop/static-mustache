package com.github.sviperll.staticmustache;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface TemplateLambda {
    
    String name() default "";
    
//    String template() default "";
//    
//    String path() default "";

}
