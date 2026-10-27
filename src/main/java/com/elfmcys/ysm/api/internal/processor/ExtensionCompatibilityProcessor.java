package com.elfmcys.ysm.api.internal.processor;

import com.elfmcys.ysm.api.annotation.YsmEventHandler;
import com.elfmcys.ysm.api.annotation.YsmExtension;
import com.elfmcys.ysm.api.annotation.YsmEntityComponent;
import com.elfmcys.ysm.api.annotation.MolangAdmissionMode;
import com.elfmcys.ysm.api.internal.processor.asm.ClassReader;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;

public final class ExtensionCompatibilityProcessor extends AbstractProcessor {
    public static final String ANNOTATION_NAME = "com.elfmcys.ysm.api.annotation.YsmExtension";
    public static final String EVENT_HANDLER_ANNOTATION_NAME = "com.elfmcys.ysm.api.annotation.YsmEventHandler";
    public static final String OWNED_PACKAGES_OPTION = "ysm.compat.ownedPackages";
    public static final String MODULE_ID_OPTION = "ysm.compat.moduleId";

    private final Map<String, ExtensionPlan> plans = new LinkedHashMap<>();
    private final Set<String> generatedCheckers = new HashSet<>();
    private final Set<String> invalidPlans = new HashSet<>();
    private final Set<String> globalOwnedPackages = new LinkedHashSet<>();
    private ClassRepository repository;
    private Messager messager;
    private boolean compilationFinished;
    private String moduleId;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        messager = processingEnv.getMessager();
        repository = new ClassRepository(processingEnv.getFiler());
        parseOwnedPackages(processingEnv.getOptions().get(OWNED_PACKAGES_OPTION), globalOwnedPackages, null);
        moduleId = processingEnv.getOptions().get(MODULE_ID_OPTION);
        if (moduleId != null) {
            moduleId = moduleId.trim();
            if (moduleId.isEmpty()) {
                messager.printMessage(Diagnostic.Kind.ERROR, "-A" + MODULE_ID_OPTION + " must not be blank.");
            }
        }
        try {
            JavacTask.instance(processingEnv).addTaskListener(new GeneratedClassListener());
        } catch (RuntimeException exception) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@YsmExtension requires the Java 17 javac compiler and its JavacTask callbacks: "
                    + exception.getMessage());
        }
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("*");
    }

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of(OWNED_PACKAGES_OPTION, MODULE_ID_OPTION);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_17;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }

        TypeElement extensionAnnotation = processingEnv.getElementUtils().getTypeElement(ANNOTATION_NAME);
        TypeElement eventHandlerAnnotation =
            processingEnv.getElementUtils().getTypeElement(EVENT_HANDLER_ANNOTATION_NAME);
        if (extensionAnnotation == null && eventHandlerAnnotation == null) {
            return false;
        }

        Set<String> touchedOwners = new LinkedHashSet<>();
        TypeElement molangAnnotation =
            processingEnv.getElementUtils().getTypeElement(YsmEntityComponent.class.getName());
        if (molangAnnotation != null)
            for (Element element : roundEnv.getElementsAnnotatedWith(molangAnnotation)) {
                TypeElement owner = (TypeElement)element;
                if (owner.getAnnotation(YsmEntityComponent.class).mode() == MolangAdmissionMode.MANUAL)
                    continue;
                ExtensionPlan plan = planFor(owner);
                touchedOwners.add(plan.ownerBinaryName);
                ExtensionPlan.CheckGroup group = new ExtensionPlan.CheckGroup("$class", "check", "CLIENT", true);
                group.ownedPackages.addAll(globalOwnedPackages);
                plan.classGroup = group;
            }
        if (eventHandlerAnnotation != null) {
            List<? extends Element> eventHandlers =
                new ArrayList<>(roundEnv.getElementsAnnotatedWith(eventHandlerAnnotation));
            eventHandlers.sort(Comparator.comparing(this::stableElementName));
            for (Element element : eventHandlers) {
                if (element.getKind() != ElementKind.CLASS) {
                    messager.printMessage(Diagnostic.Kind.ERROR, "@YsmEventHandler is only valid on a class.", element);
                    continue;
                }
                TypeElement owner = (TypeElement)element;
                ExtensionPlan plan = planFor(owner);
                touchedOwners.add(plan.ownerBinaryName);
                if (extensionAnnotation != null
                    && roundEnv.getElementsAnnotatedWith(extensionAnnotation).contains(element)) {
                    error(plan, element, "@YsmEventHandler must not be combined with class-level "
                        + "@YsmExtension; it already includes the same class compatibility check.");
                    continue;
                }
                if (!validateEventHandler(owner, plan)) {
                    continue;
                }
                YsmEventHandler marker = element.getAnnotation(YsmEventHandler.class);
                ExtensionPlan.CheckGroup group =
                    new ExtensionPlan.CheckGroup("$class", "check", marker.side().name(), true);
                addOwnedPackages(group, marker.value(), element);
                plan.classGroup = group;
            }
        }

        List<? extends Element> annotatedElements = extensionAnnotation == null ? List.of()
            : new ArrayList<>(roundEnv.getElementsAnnotatedWith(extensionAnnotation));
        annotatedElements.sort(Comparator.comparing(this::stableElementName));
        for (Element element : annotatedElements) {
            if (element.getKind().isClass() || element.getKind().isInterface()) {
                TypeElement owner = (TypeElement)element;
                ExtensionPlan plan = planFor(owner);
                touchedOwners.add(plan.ownerBinaryName);
                if (eventHandlerAnnotation != null
                    && roundEnv.getElementsAnnotatedWith(eventHandlerAnnotation).contains(element)) {
                    continue;
                }
                YsmExtension marker = element.getAnnotation(YsmExtension.class);
                ExtensionPlan.CheckGroup group =
                    new ExtensionPlan.CheckGroup("$class", "check", marker.side().name(), true);
                addOwnedPackages(group, marker.value(), element);
                plan.classGroup = group;
            } else if (element.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement)element;
                TypeElement owner = (TypeElement)method.getEnclosingElement();
                ExtensionPlan plan = planFor(owner);
                touchedOwners.add(plan.ownerBinaryName);
                if (plan.classGroup != null) {
                    continue;
                }
                YsmExtension marker = element.getAnnotation(YsmExtension.class);
                String methodName = method.getSimpleName().toString();
                String checkerMethod = "check" + capitalize(methodName);
                ExtensionPlan.CheckGroup group = plan.methodGroups.get(methodName);
                if (group == null) {
                    group = new ExtensionPlan.CheckGroup(methodName, checkerMethod, marker.side().name(), false);
                    plan.methodGroups.put(methodName, group);
                } else if (!group.side.equals(marker.side().name())) {
                    error(plan, element,
                        "All @YsmExtension overloads named " + methodName + " must declare the same side.");
                    continue;
                }
                group.roots.add(new ExtensionPlan.MethodKey(plan.ownerInternalName(), methodName, DescriptorUtil
                    .methodDescriptor(method, processingEnv.getTypeUtils(), processingEnv.getElementUtils())));
                addOwnedPackages(group, marker.value(), element);
            } else {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@YsmExtension is only valid on a class, interface, or method.", element);
            }
        }

        for (String owner : touchedOwners) {
            ExtensionPlan plan = plans.get(owner);
            validateCheckerMethodNames(plan);
            if (!invalidPlans.contains(owner) && generatedCheckers.add(owner)) {
                try {
                    CheckerSourceGenerator.generate(processingEnv.getFiler(), plan);
                } catch (FilerException exception) {
                    error(plan, null, "Cannot generate " + plan.checkerBinaryName()
                        + ": a source or class with that name already exists.");
                } catch (IOException exception) {
                    error(plan, null, "Cannot generate compatibility checker: " + exception.getMessage());
                }
            }
        }
        return false;
    }

    private ExtensionPlan planFor(TypeElement owner) {
        String binaryName = processingEnv.getElementUtils().getBinaryName(owner).toString();
        return plans.computeIfAbsent(binaryName, ignored -> {
            String packageName = processingEnv.getElementUtils().getPackageOf(owner).getQualifiedName().toString();
            String relativeName = packageName.isEmpty() ? binaryName : binaryName.substring(packageName.length() + 1);
            String checkerName = relativeName.replace('$', '_').replace('.', '_') + "CompatibilityChecker";
            return new ExtensionPlan(binaryName, packageName, checkerName);
        });
    }

    private void addOwnedPackages(ExtensionPlan.CheckGroup group, String[] annotationPackages, Element element) {
        group.ownedPackages.addAll(globalOwnedPackages);
        for (String value : annotationPackages) {
            parseOwnedPackages(value, group.ownedPackages, element);
        }
    }

    private boolean validateEventHandler(TypeElement owner, ExtensionPlan plan) {
        Set<Modifier> modifiers = owner.getModifiers();
        if (!modifiers.contains(Modifier.PUBLIC)) {
            error(plan, owner, "@YsmEventHandler class must be public.");
            return false;
        }
        if (modifiers.contains(Modifier.ABSTRACT)) {
            error(plan, owner, "@YsmEventHandler class must be concrete.");
            return false;
        }

        Element enclosing = owner.getEnclosingElement();
        if (enclosing instanceof TypeElement && !modifiers.contains(Modifier.STATIC)) {
            error(plan, owner, "A nested @YsmEventHandler class must be static.");
            return false;
        }
        if (!(enclosing instanceof TypeElement) && enclosing.getKind() != ElementKind.PACKAGE) {
            error(plan, owner, "@YsmEventHandler class must be top-level or a static member class.");
            return false;
        }
        Element visibilityOwner = enclosing;
        while (visibilityOwner instanceof TypeElement enclosingType) {
            if (!enclosingType.getModifiers().contains(Modifier.PUBLIC)) {
                error(plan, owner, "Every enclosing class of @YsmEventHandler must be public.");
                return false;
            }
            visibilityOwner = enclosingType.getEnclosingElement();
        }

        List<ExecutableElement> constructors = ElementFilter.constructorsIn(owner.getEnclosedElements());
        if (!constructors.isEmpty()
            && constructors.stream().noneMatch(constructor -> constructor.getParameters().isEmpty()
                && constructor.getModifiers().contains(Modifier.PUBLIC))) {
            error(plan, owner, "@YsmEventHandler class must expose a public no-argument " + "constructor.");
            return false;
        }
        return true;
    }

    private void parseOwnedPackages(String value, Set<String> target, Element element) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String part : value.split("[,;]")) {
            String prefix = part.trim();
            while (prefix.endsWith(".")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            if (prefix.isEmpty() || !isQualifiedName(prefix)) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "Invalid owned package prefix for YSM compatibility annotation: " + part, element);
                continue;
            }
            target.add(prefix);
        }
    }

    private void validateCheckerMethodNames(ExtensionPlan plan) {
        Map<String, String> owners = new HashMap<>();
        for (ExtensionPlan.CheckGroup group : plan.effectiveGroups()) {
            String previous = owners.putIfAbsent(group.checkerMethodName, group.key);
            if (previous != null && !previous.equals(group.key)) {
                error(plan, null, "Annotated method names " + previous + " and " + group.key + " both generate "
                    + group.checkerMethodName + "().");
            }
        }
    }

    private void finishCompilation() {
        if (compilationFinished) {
            return;
        }
        compilationFinished = true;
        CompatibilityAnalyzer analyzer = new CompatibilityAnalyzer(repository);
        ProbeGenerator probes = new ProbeGenerator(processingEnv.getFiler(), analyzer);
        for (ExtensionPlan plan : plans.values()) {
            if (invalidPlans.contains(plan.ownerBinaryName) || !generatedCheckers.contains(plan.ownerBinaryName)) {
                continue;
            }
            try {
                Map<String, ExtensionPlan.AnalysisResult> analyses = new LinkedHashMap<>();
                for (ExtensionPlan.CheckGroup group : plan.effectiveGroups()) {
                    ExtensionPlan.AnalysisResult analysis = analyzer.analyze(plan, group);
                    analyses.put(group.key, analysis);
                }
                PreparedSpec prepared = probes.prepare(plan, analyses);
                ManifestWriter.write(processingEnv.getFiler(), plan, prepared);
                for (var entry : analyses.entrySet()) {
                    for (ExtensionPlan.CoverageIssue issue : entry.getValue().issues) {
                        messager.printMessage(Diagnostic.Kind.WARNING, "YSM compatibility [" + entry.getKey() + "] "
                            + issue.kind() + " at " + issue.symbol() + ": " + issue.detail());
                    }
                }
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException(
                    "Failed to finalize YSM compatibility checker for " + plan.ownerBinaryName, exception);
            }
        }
        if (moduleId != null && !moduleId.isEmpty()) {
            try {
                ModuleFingerprintGenerator.write(processingEnv.getFiler(), moduleId,
                    repository.generatedBytesSnapshot());
            } catch (IOException exception) {
                throw new IllegalStateException(
                    "Failed to emit the @YsmExtension module ABI fingerprint for " + moduleId, exception);
            }
        }
    }

    private void captureGeneratedClass(TaskEvent event) {
        TypeElement element = event.getTypeElement();
        if (element == null) {
            return;
        }
        String binaryName = processingEnv.getElementUtils().getBinaryName(element).toString();
        if (binaryName.isEmpty()) {
            return;
        }
        String expectedInternalName = DescriptorUtil.internalName(binaryName);
        repository.markCurrentOutput(expectedInternalName);
        try {
            var resource = processingEnv.getFiler().getResource(StandardLocation.CLASS_OUTPUT, "",
                expectedInternalName + ".class");
            try (InputStream input = resource.openInputStream()) {
                byte[] bytes = input.readAllBytes();
                String actualName = new ClassReader(bytes).getClassName();
                repository.recordGenerated(actualName, bytes);
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // The final analysis retries through CLASS_OUTPUT after all GENERATE events complete.
        }
    }

    private void error(ExtensionPlan plan, Element element, String message) {
        invalidPlans.add(plan.ownerBinaryName);
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private String stableElementName(Element element) {
        if (element instanceof TypeElement type) {
            return processingEnv.getElementUtils().getBinaryName(type).toString();
        }
        if (element instanceof ExecutableElement method) {
            TypeElement owner = (TypeElement)method.getEnclosingElement();
            return processingEnv.getElementUtils().getBinaryName(owner) + "#" + method.getSimpleName() + DescriptorUtil
                .methodDescriptor(method, processingEnv.getTypeUtils(), processingEnv.getElementUtils());
        }
        return element.toString();
    }

    private static String capitalize(String name) {
        if (name.isEmpty()) {
            return name;
        }
        int first = name.codePointAt(0);
        return new StringBuilder(name.length()).appendCodePoint(Character.toUpperCase(first))
            .append(name.substring(Character.charCount(first))).toString();
    }

    private static boolean isQualifiedName(String value) {
        int start = 0;
        while (start < value.length()) {
            int end = value.indexOf('.', start);
            if (end < 0) {
                end = value.length();
            }
            if (end == start || !Character.isJavaIdentifierStart(value.charAt(start))) {
                return false;
            }
            for (int index = start + 1; index < end; index++) {
                if (!Character.isJavaIdentifierPart(value.charAt(index))) {
                    return false;
                }
            }
            start = end + 1;
        }
        return true;
    }

    private final class GeneratedClassListener implements TaskListener {
        @Override
        public void finished(TaskEvent event) {
            if (event.getKind() == TaskEvent.Kind.GENERATE) {
                captureGeneratedClass(event);
            } else if (event.getKind() == TaskEvent.Kind.COMPILATION) {
                finishCompilation();
            }
        }
    }
}
