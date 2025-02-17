package com.github.sviperll.staticmustache;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import org.eclipse.jdt.annotation.Nullable;


/**
 * This interface is to allow you to simulate JSON node like trees
 * without being coupled to a particularly JSON lib.
 * 
 * It is not recommended you use this extension point as it generally avoids the type checking safty of this library.
 * 
 * It is mainly used for the spec test.
 * 
 * @author agentgt
 *
 */
public interface ContextNode extends Iterable<ContextNode> {

    public static @Nullable ContextNode ofRoot(@Nullable Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof ContextNode n) {
            return n;
        }
        return new RootMapNode(o);
        
    }
    
    default @Nullable ContextNode ofChild(String name, @Nullable Object o) {
        if (o == null) {
            return null;
        }
        if ( o instanceof ContextNode) {
            throw new IllegalArgumentException("Cannot wrap MapNode around another MapNode");
        }
        return new NamedMapNode(this, o, name);
    }
    
    default @Nullable ContextNode ofChild(int index, @Nullable Object o) {
        if (o == null) {
            return null;
        }
        if ( o instanceof ContextNode) {
            throw new IllegalArgumentException("Cannot wrap MapNode around another MapNode");
        }
        return new IndexedMapNode(this, o, index);
    }
    
    /**
     * Gets a field from java.util.Map if MapNode is wrapping one.
     * This is direct access and does not check the parents.
     * 
     * Just like java.util.Map null will be returned if no field is found.
     * 
     * @param field
     * @return a child node. maybe null.
     */
    default @Nullable ContextNode get(String field) {
        Object o = object();
        ContextNode child = null;
        if (o instanceof Map<?,?> m) {
            child = ofChild(field,m.get(field));
        }
        return child;
    }
    
    /**
     * Will search up the tree for a field starting at this nodes children first.
     * @param field
     * @return null if not found
     */
    default @Nullable ContextNode find(String field) {
        /*
         * In theory we could make a special RenderingContext for MapNode
         * to go up the stack (generated code) but it would probably look similar
         * to the following.
         */
        ContextNode child = get(field);
        if (child != null) {
            return child;
        }
        var parent = parent();
        if (parent != null && parent != this) {
            child = parent.find(field);
            if (child != null) {
                child = ofChild(field, child.object());
            }
        }
        return child;
    }
    
    public Object object();
    
    default String renderString() {
        return String.valueOf(object());
    }
    
    default @Nullable ContextNode parent() {
        return null;
    }
    
    @Override
    default Iterator<ContextNode> iterator() {
        Object o = object();
        if (o instanceof Iterable<?> it) {
            AtomicInteger index = new AtomicInteger();
            return StreamSupport.stream(it.spliterator(), false)
                    .map( i -> this.ofChild(index.getAndIncrement(),  i)).iterator();
        }
        else if (isFalsey()) {
            return Collections.emptyIterator();
        }
        return Collections.singletonList(this).iterator();
    }
    
    default boolean isFalsey() {
        Object o = object();
        return Boolean.FALSE.equals(o);
    }
    
    public record RootMapNode(Object object) implements ContextNode {
        @Override
        public String toString() {
            return renderString();
        }
    }
    
    public record NamedMapNode(ContextNode parent, Object object, String name) implements ContextNode {
        @Override
        public String toString() {
            return renderString();
        }
    }
    
    public record IndexedMapNode(ContextNode parent, Object object, int index) implements ContextNode {
        @Override
        public String toString() {
            return renderString();
        }
    }
}
