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
package com.snaphop.staticmustache.apt;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import org.kohsuke.MetaInfServices;

import com.github.sviperll.staticmustache.GenerateRenderableAdapter;
import com.github.sviperll.staticmustache.GenerateRenderableAdapters;
import com.github.sviperll.staticmustache.Template;
import com.github.sviperll.staticmustache.TemplateCompilerFlags;
import com.github.sviperll.staticmustache.context.JavaLanguageModel;
import com.github.sviperll.staticmustache.context.RenderingCodeGenerator;
import com.github.sviperll.staticmustache.context.TemplateCompilerContext;
import com.github.sviperll.staticmustache.context.VariableContext;
import com.github.sviperll.staticmustache.meta.ElementMessage;
import com.github.sviperll.staticmustache.text.LayoutFunction;
import com.github.sviperll.staticmustache.text.Layoutable;
import com.github.sviperll.staticmustache.text.RenderFunction;
import com.github.sviperll.staticmustache.text.Renderable;
import com.github.sviperll.staticmustache.text.RendererDefinition;
import com.github.sviperll.staticmustache.text.formats.TextFormat;
import com.snaphop.staticmustache.apt.TemplateCompilerLike.TemplateCompilerType;

@MetaInfServices(value=Processor.class)
@SupportedAnnotationTypes("*")
public class GenerateRenderableAdapterProcessor extends AbstractProcessor {
	
	@Override
	public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latest();
	}
	
    private static String formatErrorMessage(Position position, String message) {
        String formatString = "%s:%d: error: %s%n%s%n%s%nsymbol: mustache directive%nlocation: mustache template";
        Object[] fields = new Object[] {
            position.fileName(),
            position.row(),
            message,
            position.currentLine(),
            columnPositioningString(position.col()),
        };
        return String.format(formatString, fields);
    }

    private static String columnPositioningString(int col) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < col - 1; i++)
            builder.append(' ');
        builder.append('^');
        return builder.toString();
    }

    private final List<ElementMessage> errors = new ArrayList<ElementMessage>();

    @Override
    public boolean process(Set<? extends TypeElement> processEnnotations,
                           RoundEnvironment roundEnv) {
        try {
            return _process(processEnnotations, roundEnv);
        } catch (AnnotatedException e) {
            e.report(processingEnv.getMessager());
            return true;
        }
    }
    
    private boolean _process(Set<? extends TypeElement> processEnnotations,
                           RoundEnvironment roundEnv)  throws AnnotatedException {
        if (roundEnv.processingOver()) {
            for (ElementMessage error: errors) {
                TypeElement element = processingEnv.getElementUtils().getTypeElement(error.qualifiedElementName());
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, error.message(), element);
            }
        } else {
            /*
             * Lets just bind the damn utils so that we do not have to pass them around everywhere
             */
            JavaLanguageModel.createInstance(processingEnv.getTypeUtils(), processingEnv.getElementUtils(), processingEnv.getMessager());
            Element generateRenderableAdapterElement = processingEnv.getElementUtils().getTypeElement(GenerateRenderableAdapter.class.getName());
            for (Element element: roundEnv.getElementsAnnotatedWith(GenerateRenderableAdapter.class)) {
                TypeElement classElement = (TypeElement)element;
                List<? extends AnnotationMirror> annotationMirrors = element.getAnnotationMirrors();
                AnnotationMirror directive = null;
                for (AnnotationMirror annotationMirror: annotationMirrors) {
                    if (processingEnv.getTypeUtils().isSubtype(annotationMirror.getAnnotationType(), generateRenderableAdapterElement.asType()))
                        directive = annotationMirror;
                }
                writeRenderableAdapterClass(classElement, directive);
            }
            Element generateRenderableAdaptersElement = processingEnv.getElementUtils().getTypeElement(GenerateRenderableAdapters.class.getName());
            for (Element element: roundEnv.getElementsAnnotatedWith(GenerateRenderableAdapters.class)) {
                TypeElement classElement = (TypeElement)element;
                List<? extends AnnotationMirror> annotationMirrors = element.getAnnotationMirrors();
                for (AnnotationMirror mirror: annotationMirrors) {
                    if (processingEnv.getTypeUtils().isSubtype(mirror.getAnnotationType(), generateRenderableAdaptersElement.asType())) {
                        Map<? extends ExecutableElement, ? extends AnnotationValue> elementValues = mirror.getElementValues();
                        for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry: elementValues.entrySet()) {
                            if (entry.getKey().getSimpleName().contentEquals("value")) {
                                @SuppressWarnings("unchecked")
                                List<? extends AnnotationValue> directives = (List<? extends AnnotationValue>)entry.getValue().getValue();
                                for (AnnotationValue directiveValue: directives) {
                                    AnnotationMirror directive = (AnnotationMirror)directiveValue.getValue();
                                    writeRenderableAdapterClass(classElement, directive);
                                }
                            }
                        }
                    }
                }
            }
        }
        return true;
    }
    
    
    private String resolveBasePath(TypeElement element) {
        PackageElement packageElement = processingEnv.getElementUtils().getPackageOf(element);
        TemplateBasePathPrism prism = TemplateBasePathPrism.getInstanceOn(packageElement);
        if (prism == null) {
            return "";
        }
        String basePath = prism.value();
        if (basePath.equals("")) {
            basePath = packageElement.getQualifiedName().toString().replace(".", "/") + "/";
        }
        return basePath;
    }
    
    private List<String> resolveBaseInterface(TypeElement element) {
        PackageElement packageElement = processingEnv.getElementUtils().getPackageOf(element);
        TemplateInterfacePrism prism = TemplateInterfacePrism.getInstanceOn(packageElement);
        if (prism != null) {
            var tm = prism.value();
            return List.of(getTypeName(tm));
        }
        return List.of();
    }
    
    private Map<String, NamedTemplate> resolveTemplatePaths(TypeElement element) {

        Map<String, NamedTemplate> paths = new LinkedHashMap<>();
        var prism = TemplateMappingPrism.getInstanceOn(element);
        if (prism != null) {
            var tps = prism.value();
            for (TemplatePrism tp : tps) {
                NamedTemplate nt;

                if (! tp.path().isBlank() ) {
                    nt = new NamedTemplate.FileTemplate(tp.name(), tp.path());
                }
                else if (! tp.template().equals(Template.NOT_SET)) {
                    nt = new NamedTemplate.InlineTemplate(tp.name(), tp.template());
                }
                else {
                    nt = new NamedTemplate.FileTemplate(tp.name(), tp.name());

                }
                paths.put(tp.name(), nt);
            }
        }
        return paths;
    }
    
    private Set<TemplateCompilerFlags.Flag> resolveFlags(TypeElement element) {
        var prism = TemplateCompilerFlagsPrism.getInstanceOn(element);
        var flags = EnumSet.noneOf(TemplateCompilerFlags.Flag.class);
        if (prism != null) {
            prism.flags().stream().map(TemplateCompilerFlags.Flag::valueOf).forEach(flags::add);
        }
        return Collections.unmodifiableSet(flags);
    }

    
    
    private String getTypeName(TypeMirror tm) {
       var e = ((DeclaredType) tm).asElement();
       var te = (TypeElement) e;
       return te.getQualifiedName().toString();
    }
    
    private FormatterTypes getFormatterTypes(TypeElement element) {
        PackageElement packageElement = processingEnv.getElementUtils().getPackageOf(element);
        TemplateFormatterTypesPrism prism = TemplateFormatterTypesPrism.getInstanceOn(packageElement);
        if (prism == null) {
            return FormatterTypes.acceptOnlyKnownTypes();
        }
        List<String> classNames = prism.types().stream().map(tm -> getTypeName(tm)).toList();
        List<String> patterns = prism.patterns().stream().toList();
        return new FormatterTypes.ConfiguredFormatterTypes(classNames, patterns);
    }
    
    
    private void writeRenderableAdapterClass(TypeElement element, AnnotationMirror directiveMirror) throws AnnotatedException {
        Method templateFormatMethod;
        Method adapterNameMethod;
        Method templateMethod;
        Method charsetMethod;
        Method isLayoutMethod;
        try {
            templateFormatMethod = GenerateRenderableAdapter.class.getDeclaredMethod("templateFormat");
            adapterNameMethod = GenerateRenderableAdapter.class.getDeclaredMethod("adapterName");
            templateMethod = GenerateRenderableAdapter.class.getDeclaredMethod("template");
            charsetMethod = GenerateRenderableAdapter.class.getDeclaredMethod("charset");
            isLayoutMethod = GenerateRenderableAdapter.class.getDeclaredMethod("isLayout");
        } catch (NoSuchMethodException ex) {
            throw new RuntimeException(ex);
        } catch (SecurityException ex) {
            throw new RuntimeException(ex);
        }

        String templatePath = null;
        String directiveAdapterName = null;
        String directiveCharset = null;
        TypeElement templateFormatElement = null;
        Boolean isLayout = null;
        Map<? extends ExecutableElement, ? extends AnnotationValue> annotationValues = processingEnv.getElementUtils().getElementValuesWithDefaults(directiveMirror);
        for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry: annotationValues.entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(templateFormatMethod.getName())) {
                AnnotationValue value = entry.getValue();
                Object templateFormatValue = value.getValue();
                if (!(templateFormatValue instanceof DeclaredType))
                    throw new ClassCastException("Expecting DeclaredType got " + templateFormatValue.getClass().getName() + ": " + templateFormatValue);
                DeclaredType templateFormatType = (DeclaredType)templateFormatValue;
                templateFormatElement = (TypeElement)templateFormatType.asElement();
            } else if (entry.getKey().getSimpleName().contentEquals(adapterNameMethod.getName())) {
                directiveAdapterName = (String)entry.getValue().getValue();
            } else if (entry.getKey().getSimpleName().contentEquals(templateMethod.getName())) {
                templatePath = (String)entry.getValue().getValue();
            } else if (entry.getKey().getSimpleName().contentEquals(charsetMethod.getName())) {
                directiveCharset = (String)entry.getValue().getValue();
            } else if (entry.getKey().getSimpleName().contentEquals(isLayoutMethod.getName())) {
                isLayout = (Boolean)entry.getValue().getValue();
            }
        }
        if (templateFormatElement == null)
            throw new AnnotatedException(element,templateFormatMethod.getName() + " should always be defined in " + GenerateRenderableAdapter.class.getName() + " annotation");
        if (directiveAdapterName == null)
            throw new AnnotatedException(element, adapterNameMethod.getName() + " should always be defined in " + GenerateRenderableAdapter.class.getName() + " annotation");
        if (directiveCharset == null)
            throw new AnnotatedException(element, charsetMethod.getName() + " should always be defined in " + GenerateRenderableAdapter.class.getName() + " annotation");
        if (templatePath == null)
            throw new AnnotatedException(element,templateMethod.getName() + " should always be defined in " + GenerateRenderableAdapter.class.getName() + " annotation");
        if (isLayout == null)
            throw new AnnotatedException(element, isLayoutMethod.getName() + " should always be defined in " + GenerateRenderableAdapter.class.getName() + " annotation");
        String adapterClassSimpleName;
        if (!directiveAdapterName.equals(":auto"))
            adapterClassSimpleName = directiveAdapterName;
        else {
            //adapterClassSimpleName = (isLayout ? "Layoutable" : "Renderable") + element.getSimpleName().toString() + "Adapter";
            adapterClassSimpleName = element.getSimpleName().toString() + (isLayout ? "Layoutable" : "Renderer");

        }
        Charset templateCharset = directiveCharset.equals(":default") ? Charset.defaultCharset() : Charset.forName(directiveCharset);
        try {
            TextFormat templateFormatAnnotation = templateFormatElement.getAnnotation(TextFormat.class);
            if (templateFormatAnnotation == null) {
                throw new DeclarationException(templateFormatElement.getQualifiedName() + " class is used as a template format, but not marked with " + TextFormat.class.getName() + " annotation");
            }
            if (!element.getTypeParameters().isEmpty()) {
                throw new DeclarationException("Can't generate renderable adapter for class with type variables: " + element.getQualifiedName());
            }
            StringWriter stringWriter = new StringWriter();
            
            String basePath = resolveBasePath(element);
            if (! templatePath.startsWith("/")) {
                templatePath = basePath + templatePath;
            }
            List<String> ifaces = resolveBaseInterface(element);
            FormatterTypes formatterTypes = getFormatterTypes(element);
            Map<String,NamedTemplate> templatePaths = resolveTemplatePaths(element);
            Set<TemplateCompilerFlags.Flag> flags = resolveFlags(element);
            
            try (SwitchablePrintWriter switchablePrintWriter = SwitchablePrintWriter.createInstance(stringWriter)){
                //FileObject templateBinaryResource = processingEnv.getFiler().getResource(StandardLocation.CLASS_OUTPUT, "", templatePath);
                
                //TODO pass basepath
                TextFileObject templateResource = new TextFileObject(processingEnv, templateCharset);
                JavaLanguageModel javaModel = JavaLanguageModel.getInstance();
                RenderingCodeGenerator codeGenerator = RenderingCodeGenerator.createInstance(javaModel, formatterTypes, templateFormatElement);
                CodeWriter codeWriter = new CodeWriter(switchablePrintWriter, codeGenerator, templatePaths, flags);
                ClassWriter writer = new ClassWriter(codeWriter, element, templateResource, templatePath);

                writer.writeRenderableAdapterClass(adapterClassSimpleName, isLayout, templateFormatElement, ifaces);
            }
            PackageElement packageElement = processingEnv.getElementUtils().getPackageOf(element);
            String packageName = packageElement.getQualifiedName().toString();
            String adapterClassName = packageName + "." + adapterClassSimpleName;
            JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(adapterClassName, element);
            OutputStream stream = sourceFile.openOutputStream();
            try {
                Writer outputWriter = new OutputStreamWriter(stream, Charset.defaultCharset());
                try {
                    outputWriter.append(stringWriter.getBuffer().toString());
                } finally {
                    outputWriter.close();
                }
            } finally {
                try {
                    stream.close();
                } catch (Exception ex) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, Throwables.render(ex), element);
                }
            }
        } catch (ProcessingException ex) {
            String errorMessage = formatErrorMessage(ex.position(), ex.getMessage());
            errors.add(ElementMessage.of(element, errorMessage));
        } catch (DeclarationException ex) {
            errors.add(ElementMessage.of(element, ex.toString()));
        } catch (IOException ex) {
            errors.add(ElementMessage.of(element, Throwables.render(ex)));
        } catch (RuntimeException ex) {
            errors.add(ElementMessage.of(element, Throwables.render(ex)));
        }
    }

    private class ClassWriter {
        private final CodeWriter codeWriter;
        private final TypeElement element;
        private final TextFileObject templateLoader;
        private final String templateName;
        ClassWriter(CodeWriter compilerManager, TypeElement element, TextFileObject templateLoader, String templateName) {
            this.codeWriter = compilerManager;
            this.element = element;
            this.templateName = templateName;
            this.templateLoader = templateLoader;
        }

        void println(String s) {
            codeWriter.println(s);
        }

        private void writeRenderableAdapterClass(String adapterClassSimpleName, Boolean isLayout,
                       TypeElement templateFormatElement, List<String> ifaces) throws IOException, ProcessingException, AnnotatedException {
            String className = element.getQualifiedName().toString();
            PackageElement packageElement = processingEnv.getElementUtils().getPackageOf(element);
            String packageName = packageElement.getQualifiedName().toString();
            TextFormat templateFormatAnnotation = templateFormatElement.getAnnotation(TextFormat.class);

            List<String> ifaceStrings = new ArrayList<String>(ifaces);
            //ifaces.stream().map(c -> c.getName()).forEach(ifaceStrings::add);
            
            if (isLayout) ifaceStrings.add(Layoutable.class.getName() + "<" + templateFormatElement.getQualifiedName() + ">");
            
            String implementsString = ifaceStrings.isEmpty() ? "" : " implements " +
                    ifaceStrings.stream().collect(Collectors.joining(", "));
            
            String extendsString = isLayout ? "" :  " extends " 
                    + Renderable.class.getName() + "<" + templateFormatElement.getQualifiedName() + ">";
            
            String modifier = element.getModifiers().contains(Modifier.PUBLIC) ? "public " : "";
            
            println("package " + packageName + ";");
            println("// @javax.annotation.Generated(\"" + GenerateRenderableAdapterProcessor.class.getName() + "\")");
            println(modifier + "class " + adapterClassSimpleName + extendsString + implementsString +" {");
            println("    public static final String TEMPLATE = \"" + templateName + "\";");
            println("    private final " + className + " data;");
            String constructorModifier = isLayout ? "public" : "private";
            println("    " + constructorModifier + " " + adapterClassSimpleName + "(" + className + " data) {");
            println("        this.data = data;");
            println("    }");
            
            println("    @Override");
            println("    public String " + "getTemplate() {");
            println("        return TEMPLATE;");
            println("    }");
            
            println("    @Override");
            println("    public Object " + "getContext() {");
            println("        return this.data;");
            println("    }");
            if (!isLayout) {
            	
                println("    public static " + RenderFunction.class.getName() + " of(" + className + " data) {");
                println("        return new " + adapterClassSimpleName + "(data);");
                println("    }");
                
                String adapterRendererClassSimpleName = adapterClassSimpleName + "Renderer";
                String adapterRendererClassName = adapterClassSimpleName + "." + adapterRendererClassSimpleName;

                println("    @Override");
                println("    protected " + RendererDefinition.class.getName() + " createRenderer(" + Appendable.class.getName() + " unescapedWriter) {");
                println("        " + Appendable.class.getName() + " writer = " + templateFormatElement.getQualifiedName() + "." + templateFormatAnnotation.createEscapingAppendableMethodName() + "(unescapedWriter);");
                println("        return " + RendererDefinition.class.getName() + ".of(new " + adapterRendererClassName + "(data, writer, unescapedWriter));");
                println("    }");

                writeRendererDefinitionClass(adapterRendererClassSimpleName, TemplateCompilerType.SIMPLE);
            } else {
            	
                println("    public static " + LayoutFunction.class.getName() + " of(" + className + " data) {");
                println("        return new " + adapterClassSimpleName + "(data);");
                println("    }");
                
                String adapterHeaderRendererClassSimpleName = adapterClassSimpleName + "HeaderRenderer";
                String adapterHeaderRendererClassName = adapterClassSimpleName + "." + adapterHeaderRendererClassSimpleName;
                String adapterFooterRendererClassSimpleName = adapterClassSimpleName + "FooterRenderer";
                String adapterFooterRendererClassName = adapterClassSimpleName + "." + adapterFooterRendererClassSimpleName;
                println("    @Override");
                println("    public " + RendererDefinition.class.getName() + " createHeaderRenderer(" + Appendable.class.getName() + " unescapedWriter) {");
                println("        " + Appendable.class.getName() + " writer = " + templateFormatElement.getQualifiedName() + "." + templateFormatAnnotation.createEscapingAppendableMethodName() + "(unescapedWriter);");
                println("        return " + RendererDefinition.class.getName() + ".of(new " + adapterHeaderRendererClassName + "(data, writer, unescapedWriter));");
                println("    }");
                println("    @Override");
                println("    public " + RendererDefinition.class.getName() + " createFooterRenderer(" + Appendable.class.getName() + " unescapedWriter) {");
                println("        " + Appendable.class.getName() + " writer = " + templateFormatElement.getQualifiedName() + "." + templateFormatAnnotation.createEscapingAppendableMethodName() + "(unescapedWriter);");
                println("        return " + RendererDefinition.class.getName() + ".of(new " + adapterFooterRendererClassName + "(data, writer, unescapedWriter));");
                println("    }");

                writeRendererDefinitionClass(adapterHeaderRendererClassSimpleName, TemplateCompilerType.HEADER);
                writeRendererDefinitionClass(adapterFooterRendererClassSimpleName, TemplateCompilerType.FOOTER);
            }
            println("}");
        }

        private void writeRendererDefinitionClass(String adapterRendererClassSimpleName, TemplateCompilerType templateCompilerType ) throws IOException, ProcessingException, AnnotatedException {
            String className = element.getQualifiedName().toString();
            println("    private static class " + adapterRendererClassSimpleName + " implements " + RendererDefinition.class.getName() + " {");

            VariableContext variables = VariableContext.createDefaultContext();
            String dataName = variables.introduceNewNameLike("data");
            TemplateCompilerContext context = codeWriter.createTemplateContext(templateName, element, dataName, variables);
            println("        private final " + Appendable.class.getName() + " " + variables.unescapedWriter() + ";");
            println("        private final " + Appendable.class.getName() + " " + variables.writer() + ";");
            println("        private final " + className + " " + dataName + ";");
            println("        public " + adapterRendererClassSimpleName 
                    + "(" + className + " data, " 
                    + Appendable.class.getName() + " writer, " 
                    + Appendable.class.getName() + " unescapedWriter) {");
            println("            this." + variables.writer() + " = writer;");
            println("            this." + variables.unescapedWriter() + " = unescapedWriter;");
            println("            this." + dataName + " = data;");
            println("        }");
            println("        @Override");
            println("        public void render() throws " + IOException.class.getName() + " {");
            codeWriter.compileTemplate(templateLoader, context, templateCompilerType);
            println("        }");
            println("    }");
        }
    }
}
