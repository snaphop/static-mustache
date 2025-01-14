/*
 * Copyright (c) 2014, Victor Nazarov <asviraspossible@gmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation and/or
 *     other materials provided with the distribution.
 *
 *  3. Neither the name of the copyright holder nor the names of its contributors
 *     may be used to endorse or promote products derived from this software
 *     without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 *  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 *  THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 *  IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 *  ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 *  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *   LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 *  ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 *  EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.github.sviperll.staticmustache.context;

import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;

import org.eclipse.jdt.annotation.NonNull;

import com.github.sviperll.staticmustache.context.Lambda.Lambdas;
import com.github.sviperll.staticmustache.context.TemplateCompilerContext.ContextType;
import com.github.sviperll.staticmustache.context.types.KnownType;
import com.github.sviperll.staticmustache.context.types.KnownTypes;
import com.github.sviperll.staticmustache.context.types.NativeType;
import com.github.sviperll.staticmustache.context.types.ObjectType;
import com.snaphop.staticmustache.apt.AnnotatedException;
import com.snaphop.staticmustache.apt.FormatterTypes;
import com.snaphop.staticmustache.apt.TemplateLambdaPrism;

/**
 * This class allows to create TemplateCompilerContext instance
 *
 * @author Victor Nazarov <asviraspossible@gmail.com>
 */
public class RenderingCodeGenerator {
    /**
     * Creates instance.
     *
     * @param javaModel language model to allow java expression manipulation
     * @param formatClass type declaration denoting text format. formatClass should not contain type variables.
     * @return
     */
    public static RenderingCodeGenerator createInstance(JavaLanguageModel javaModel, FormatterTypes formatterTypes, TypeElement formatClass
            ) {
        return new RenderingCodeGenerator(javaModel.knownTypes(), javaModel, formatterTypes, formatClass);
    }

    private final KnownTypes knownTypes;
    private final JavaLanguageModel javaModel;
    private final FormatterTypes formatterTypes;
    private final TypeElement templateFormatElement;

    private RenderingCodeGenerator(KnownTypes types, JavaLanguageModel javaModel, FormatterTypes formatterTypes, TypeElement formatClass) {
        this.knownTypes = types;
        this.javaModel = javaModel;
        this.formatterTypes = formatterTypes;
        this.templateFormatElement = formatClass;

    }
    String generateRenderingCode(JavaExpression expression, VariableContext variables, String path) throws TypeException {
        TypeMirror type = expression.type();
        final String text = expression.text();
        //String path = expression.path();
        if (type instanceof WildcardType) {
            return generateRenderingCode(javaModel.expression(text, ((WildcardType)type).getExtendsBound()), variables, path);
        }
        
        
        if (javaModel.isSubtype(type, javaModel.getGenericDeclaredType(knownTypes._Renderable.typeElement()))) {
            return text + ".render(" + variables.unescapedWriter()  + "); ";
        }
        
        KnownType knownType = javaModel.resolveType(type).orElse(null);
        
        if (knownType != null && knownType instanceof ObjectType) {
            String cname = knownType.renderClassName() + ".class";
            return renderFormatCall(variables, path, text, cname);

        }
        else if (knownType != null && knownType instanceof NativeType) {
            return "format(" + variables.writer() + ", " + "\"" + path + "\"" + ", " + text + ");"; 
        }
        else if (type instanceof @NonNull DeclaredType dt) {
            String cname = javaModel.eraseType(dt);
            if (formatterTypes.isMatch(cname)) {
                return renderFormatCall(variables, path, text, cname + ".class");
            }
        }
        
        throw new TypeException(MessageFormat
                .format("Can''t render {0} expression of {1} type as it is not an allowed type to format. ", text, type));
    }
    private String renderFormatCall(VariableContext variables, String path, String text, String cname) {
        return "format(" + variables.writer() //
                + ", " + "\"" + path + "\"" //
                + ", " + cname //
                + ", " + text + ");";
    }
    
    @SuppressWarnings("null")
    private Lambdas resolveLambdas(TypeElement element, JavaExpression root) throws AnnotatedException {
        
        var all = javaModel.getElements().getAllMembers(element);
        var lambdaMethods = ElementFilter.methodsIn(all).stream()
                .filter(e -> 
                        e.getModifiers().contains(Modifier.PUBLIC) 
                        && e.getReturnType().getKind() != TypeKind.VOID)
                .filter(e -> TemplateLambdaPrism.getInstanceOn(e) != null)
                .toList();
        Map<String, Lambda> lambdas = new LinkedHashMap<>();
        
        for (ExecutableElement lm : lambdaMethods) {
            TemplateLambdaPrism p = TemplateLambdaPrism.getInstanceOn(lm);
            String name = p.name();
            //String path = p.path();
            Lambda lambda;
            try {
                lambda = Lambda.of(root,lm, name);
            } catch (Exception e1) {
                throw new AnnotatedException(e1.getMessage(), lm);
            }
            //TODO check for name collisions
            lambdas.put(lambda.name(), lambda);
        }
        
        return new Lambdas(lambdas);
    }

    /**
     * creates TemplateCompilerContext instance.
     *
     * @param element root of the data binding context. Element should not contain type-variables.
     * @param expression java expression of type corresponding to given TypeElement
     * @param variables declared variables to use in generated code
     * @return new TemplateCompilerContext
     * @throws AnnotatedException 
     */
    public TemplateCompilerContext createTemplateCompilerContext(TemplateStack templateStack, TypeElement element, String expression, VariableContext variables) throws AnnotatedException {
        JavaExpression javaExpression = javaModel.expression(expression, javaModel.getDeclaredType(element));
        RootRenderingContext root = new RootRenderingContext(variables);
        Lambdas lambdas = resolveLambdas(element, javaExpression);
        
        RenderingContext rootRenderingContext;
        // A special case scenario where the root is a java.util.Map or our custom MapNode... not recommended but useful for spec tests

        if (javaModel.isType(element.asType(), knownTypes._MapNode)) {
            rootRenderingContext = new ContextNodeRenderingContext(javaExpression, element, root);
        }
        else if (javaModel.isType(element.asType(), knownTypes._Map)) {
            rootRenderingContext = new MapRenderingContext(javaExpression, element, root);
       }
        else {
             rootRenderingContext = new DeclaredTypeRenderingContext(javaExpression, element, root);
        }
        return new TemplateCompilerContext(templateStack, lambdas, this, variables, rootRenderingContext, ContextType.ROOT);
    }
    
    public TemplateCompilerContext createTemplateCompilerContext(String templateName, TypeElement element, String expression, VariableContext variables) throws AnnotatedException {
        return createTemplateCompilerContext(TemplateStack.of(templateName), element, expression, variables);
    }
    
    public TemplateCompilerContext createTemplateCompilerContext(TemplateStack templateStack, DeclaredType type, String expression, VariableContext variables) throws AnnotatedException {
        TypeElement element = javaModel.asElement(type);
        return createTemplateCompilerContext(templateStack, element, expression, variables);
    }

    RenderingContext createRenderingContext(ContextType childType, JavaExpression expression, RenderingContext enclosing) throws TypeException {
        if (javaModel.isType(expression.type(), knownTypes._MapNode)) {
            return switch(childType) {
            case SECTION: {
                yield createIterableContext(childType, expression, enclosing);
            }
            default: {
                yield createMapNodeContext(expression, enclosing);
            }
            };
        } else if (expression.type() instanceof WildcardType) {
            WildcardType wildcardType = (WildcardType)expression.type();
            var extendsBound = wildcardType.getExtendsBound();
            return createRenderingContext(childType, javaModel.expression(expression.text(), extendsBound), enclosing);
        } else if (javaModel.isSubtype(expression.type(), javaModel.getGenericDeclaredType(knownTypes._Layoutable.typeElement()))) {
            if (!javaModel.isSubtype(expression.type(), javaModel.getDeclaredType(knownTypes._Layoutable.typeElement(), javaModel.getDeclaredType(templateFormatElement)))) {
                throw new TypeException(MessageFormat.format("Can''t render {0} expression of {1} type: expression is Layoutable, but wrong format", expression.text(), expression.type()));
            } else {
                VariableContext context = enclosing.createEnclosedVariableContext();
                return new LayoutableRenderingContext(expression, context, enclosing);
            }
        } else if (javaModel.isType(expression.type(), knownTypes._boolean) && ! childType.isVar()) {
            return new BooleanRenderingContext(expression.text(), enclosing);
        } else if (javaModel.isType(expression.type(), knownTypes._Boolean) && ! childType.isVar()) {
            RenderingContext nullableContext = nullableRenderingContext(expression, enclosing);
            BooleanRenderingContext booleanContext = new BooleanRenderingContext(expression.text(), nullableContext);
            return booleanContext;
        } else if (javaModel.isType(expression.type(), knownTypes._Optional)) {
            DeclaredType declaredType = (DeclaredType)expression.type();
            // We do not give optional a nullable rendering context. If you make optional nullable your are dumb.
            return new OptionalRenderingContext(expression, javaModel.asElement(declaredType), enclosing);
        } else if (javaModel.isType(expression.type(), knownTypes._Iterable)) {
            return createIterableContext(childType, expression, enclosing);
        } else if (javaModel.isType(expression.type(), knownTypes._Map)) {
            return createMapContext(expression, enclosing);
        } else if (expression.type().getKind() == TypeKind.ARRAY) {
            RenderingContext nullable = nullableRenderingContext(expression, enclosing);
            VariableContext variableContext = nullable.createEnclosedVariableContext();
            String indexVariableName = variableContext.introduceNewNameLike("i");
            RenderingContext variables = new VariablesRenderingContext(variableContext, nullable);
            ArrayRenderingContext array = new ArrayRenderingContext(expression, indexVariableName, variables);
            return createRenderingContext(childType,array.componentExpession(), array);
        } else if (expression.type().getKind() == TypeKind.DECLARED) {
            DeclaredType declaredType = (DeclaredType)expression.type();
            RenderingContext ctx = switch (childType) {
            case ESCAPED_VAR, UNESCAPED_VAR -> enclosing;
            case PATH, INVERTED, PARENT_PARTIAL, SECTION -> nullableRenderingContext(expression, enclosing);
            case ROOT -> throw new UnsupportedOperationException("Unimplemented case: " + childType);
            default -> throw new IllegalArgumentException("Unexpected value: " + childType);
            
            };
            DeclaredTypeRenderingContext declaredContext = new DeclaredTypeRenderingContext(expression, javaModel.asElement(declaredType), ctx);
            return declaredContext;
        } else {
            return new NoDataContext(expression, enclosing);
        }
    }
    private RenderingContext createMapContext(JavaExpression expression, RenderingContext enclosing) {
        RenderingContext nullable = nullableRenderingContext(expression, enclosing);
        DeclaredType mapType = (DeclaredType) expression.type();
        MapRenderingContext map = new MapRenderingContext(expression, javaModel.asElement(mapType), nullable);
        return map;
    }
    
    private RenderingContext createMapNodeContext(JavaExpression expression, RenderingContext enclosing) {
        RenderingContext nullable = nullableRenderingContext(expression, enclosing);
        DeclaredType mapType = (DeclaredType) expression.type();
        ContextNodeRenderingContext map = new ContextNodeRenderingContext(expression, javaModel.asElement(mapType), nullable);
        return map;
    }
    
    private RenderingContext createIterableContext(ContextType childType, JavaExpression expression,
            RenderingContext enclosing) throws TypeException {
        RenderingContext nullable = nullableRenderingContext(expression, enclosing);
        VariableContext variableContext = nullable.createEnclosedVariableContext();
        String elementVariableName = variableContext.introduceNewNameLike("element");
        RenderingContext variables = new VariablesRenderingContext(variableContext, nullable);
        IterableRenderingContext iterable = new IterableRenderingContext(expression, elementVariableName, variables);
        if (expression.model().isType(expression.type(), knownTypes._MapNode)) {
            return createMapNodeContext(iterable.elementExpession(), iterable);
        }
        return createRenderingContext(childType, iterable.elementExpession(), iterable);
    }

    RenderingContext createInvertedRenderingContext(JavaExpression expression, RenderingContext enclosing) throws TypeException {
        if (expression.type() instanceof WildcardType) {
            WildcardType wildcardType = (WildcardType)expression.type();
            return createRenderingContext(ContextType.INVERTED, javaModel.expression(expression.text(), wildcardType.getExtendsBound()), enclosing);
        } else if (javaModel.isType(expression.type(), knownTypes._boolean)) {
            return new BooleanRenderingContext("!(" + expression.text() + ")", enclosing);
        } else if (javaModel.isType(expression.type(), knownTypes._Boolean)) {
            return new BooleanRenderingContext("(" + expression.text() + ") == null || !(" + expression.text() + ")", enclosing);
        } else if (javaModel.isType(expression.type(), knownTypes._Optional)) {
            DeclaredType dt = (DeclaredType) expression.type();
            OptionalRenderingContext declaredContext = new OptionalRenderingContext(expression, javaModel.asElement(dt), enclosing);
            return new BooleanRenderingContext("(" + declaredContext.currentExpression().text() + ") == null", declaredContext);
        } else if (javaModel.isType(expression.type(), knownTypes._MapNode) && expression.type() instanceof DeclaredType dt) {
            //DeclaredTypeRenderingContext declaredContext = new DeclaredTypeRenderingContext(expression, javaModel.asElement(dt), enclosing);
            ContextNodeRenderingContext c = new ContextNodeRenderingContext(expression, javaModel.asElement(dt) , enclosing);
            return new BooleanRenderingContext("isFalsey(" + expression.text() + ")", c);
        } else if (expression.type() instanceof DeclaredType dt) {
            DeclaredTypeRenderingContext declaredContext = new DeclaredTypeRenderingContext(expression, javaModel.asElement(dt), enclosing);
            return new BooleanRenderingContext("(" + expression.text() + ") == null || Boolean.FALSE.equals(" + expression.text() + ")", declaredContext);
        } else if (expression.type() instanceof ArrayType) {
            return new BooleanRenderingContext("(" + expression.text() + ") == null || (" + expression.text() + ").length == 0", enclosing);
        } else
            throw new TypeException(MessageFormat.format("Can''t invert {0} expression of {1} type",
                                                         expression.text(),
                                                         expression.type()));
    }

    private RenderingContext nullableRenderingContext(JavaExpression expression, RenderingContext context) {
        if (javaModel.isSameType(expression.type(), knownTypes._Object.typeElement().asType())) {
            return new BooleanRenderingContext(expression.text() + " != null && ! Boolean.FALSE.equals(" + expression.text() + ")" , context);
        }
        return new BooleanRenderingContext(expression.text() + " != null", context);
    }
}
