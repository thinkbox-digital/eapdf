/*
 * This file is part of veraPDF Library core, a module of the veraPDF project.
 * Copyright (c) 2015-2026, veraPDF Consortium <info@verapdf.org>
 * All rights reserved.
 *
 * veraPDF Library core is free software: you can redistribute it and/or modify
 * it under the terms of either:
 *
 * The GNU General public license GPLv3+.
 * You should have received a copy of the GNU General Public License
 * along with veraPDF Library core as the LICENSE.GPL file in the root of the source
 * tree.  If not, see http://www.gnu.org/licenses/ or
 * https://www.gnu.org/licenses/gpl-3.0.en.html.
 *
 * The Mozilla Public License MPLv2+.
 * You should have received a copy of the Mozilla Public License along with
 * veraPDF Library core as the LICENSE.MPL file in the root of the source tree.
 * If a copy of the MPL was not distributed with this file, you can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
/**
 *
 */
package org.verapdf.pdfa.validation.validators;

import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.ScriptableObject;
import org.verapdf.component.ComponentDetails;
import org.verapdf.component.Components;
import org.verapdf.core.ModelParsingException;
import org.verapdf.core.ValidationException;
import org.verapdf.core.utils.ValidationProgress;
import org.verapdf.gf.model.impl.cos.GFCosDocument;
import org.verapdf.gf.model.impl.cos.IndirectObjectLinks;
import org.verapdf.model.GenericModelObject;
import org.verapdf.model.baselayer.Object;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.Location;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.pdfa.results.TestAssertion.Status;
import org.verapdf.pdfa.results.ValidationResult;
import org.verapdf.pdfa.results.ValidationResults;
import org.verapdf.pdfa.validation.profiles.*;
import org.verapdf.processor.reports.enums.JobEndStatus;

import java.net.URI;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:carl@openpreservation.org">Carl Wilson</a>
 */
public class BaseValidator implements PDFAValidator {
	private static final Logger LOGGER = Logger.getLogger(BaseValidator.class.getCanonicalName());
	public static final int DEFAULT_MAX_NUMBER_OF_DISPLAYED_FAILED_CHECKS = 100;
	private static final int MAX_CHECKS_NUMBER = 10_000;
	private static final URI componentId = URI.create("http://pdfa.verapdf.org/validators#default");
	private static final String componentName = "veraPDF Validator";
	private static final ComponentDetails componentDetails = Components.libraryDetails(componentId, componentName);
	private static final ClassValue<Boolean> TYPE_BASED_INHERITANCE = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> modelClass) {
			try {
				return modelClass.getMethod("getSuperTypes").getDeclaringClass() == GenericModelObject.class;
			} catch (NoSuchMethodException ignored) {
				return false;
			}
		}
	};
	List<FlavourValidator> validators = new LinkedList<>();
	private ScriptableObject scope;

	private final Deque<java.lang.Object> objectsStack = new ArrayDeque<>();
	private final Deque<ContextPath> objectsContext = new ArrayDeque<>();
	private long pendingIndirectObjects;
	private volatile String modelActivity;
	private final Map<String, ObjectChecks> checksByType = new HashMap<>();

	private record RuleCheck(FlavourValidator validator, Rule rule) {}
	private record ObjectChecks(RuleCheck[] rules, Variable[] variables) {}

	private static final class IndirectBatch {
		final IndirectObjectLinks links;
		final BitSet accepted;
		int next;

		IndirectBatch(IndirectObjectLinks links, BitSet accepted) {
			this.links = links;
			this.accepted = accepted;
			this.next = accepted.nextSetBit(0);
		}
	}

	// Local change: share immutable prefixes while objects wait on the frontier.
	// Capture ID/extra text when enqueued, and format only when a rule needs it.
	static record ContextPath(ContextPath parent, String link, int index, String id, String extra) implements Supplier<String> {
		@Override public String get() { return toString(); }

		@Override public String toString() {
			Deque<ContextPath> path = new ArrayDeque<>();
			for (ContextPath node = this; node != null; node = node.parent) path.push(node);
			StringBuilder text = new StringBuilder();
			while (!path.isEmpty()) {
				ContextPath node = path.pop();
				if (node.parent == null) text.append(node.link);
				else text.append('/').append(node.link).append('[').append(node.index).append(']');
				if (node.id != null) text.append('(').append(node.id).append(')');
				if (node.extra != null) text.append('{').append(node.extra).append('}');
			}
			return text.toString();
		}
	}
	protected volatile boolean abortProcessing = false;
	protected final boolean logPassedChecks;
	protected final int maxNumberOfDisplayedFailedChecks;
	private boolean showErrorMessages = false;
	protected final ValidationProgress validationProgress;
	protected volatile JobEndStatus jobEndStatus = JobEndStatus.NORMAL;

	// Local change: retain exact visited IDs on disk after a bounded in-memory prefix.
	private final SpillableIdSet idSet = new SpillableIdSet();

	protected String rootType;

	protected BaseValidator(final ValidationProfile profile) {
		this(profile, false);
	}

	protected BaseValidator(final List<ValidationProfile> profiles) {
		this(profiles, DEFAULT_MAX_NUMBER_OF_DISPLAYED_FAILED_CHECKS, false, false, false);
	}

	protected BaseValidator(final ValidationProfile profile, final boolean logPassedChecks) {
		this(profile, DEFAULT_MAX_NUMBER_OF_DISPLAYED_FAILED_CHECKS, logPassedChecks, false, false);
	}

	protected BaseValidator(final ValidationProfile profile, final int maxNumberOfDisplayedFailedChecks,
							final boolean logPassedChecks, final boolean showErrorMessages, boolean showProgress) {
		this(Collections.singletonList(profile), maxNumberOfDisplayedFailedChecks, logPassedChecks, showErrorMessages, showProgress);
	}

	protected BaseValidator(final List<ValidationProfile> profiles, final int maxNumberOfDisplayedFailedChecks,
							final boolean logPassedChecks, final boolean showErrorMessages, boolean showProgress) {
		super();
		createCompatibleValidators(profiles);
		this.maxNumberOfDisplayedFailedChecks = maxNumberOfDisplayedFailedChecks;
		this.logPassedChecks = logPassedChecks;
		this.showErrorMessages = showErrorMessages;
		this.validationProgress = new ValidationProgress(showProgress);
	}

	private void createCompatibleValidators(List<ValidationProfile> profiles) {
		if (profiles.isEmpty()) {
			return;
		}
		PDFAFlavour flavour = profiles.get(0).getPDFAFlavour();
		PDFAFlavour.PDFSpecification pdfSpecification = flavour.getPart().getPdfSpecification();
		for (ValidationProfile profile : profiles) {
			PDFAFlavour currentFlavour = profile.getPDFAFlavour();
			PDFAFlavour.PDFSpecification currentPDFSpecification = currentFlavour.getPart().getPdfSpecification();
			if (pdfSpecification == currentPDFSpecification) {
				validators.add(new FlavourValidator(profile));
			} else {
				LOGGER.log(Level.WARNING,String.format("PDF version %s of detected flavour %s is incompatible with the PDF version %s of other detected flavour %s. The validation of flavour %s is skipped",
						currentPDFSpecification, currentFlavour, pdfSpecification, flavour, currentFlavour));
			}
		}
	}
	

	@Override
	public ValidationProfile getProfile() {
		return this.validators.isEmpty() ? null : this.validators.get(0).getProfile();
	}
	
	private List<PDFAFlavour> getFlavours() {
		List<PDFAFlavour> flavours = new LinkedList<>();
		for (FlavourValidator validator : validators) {
			flavours.add(validator.getProfile().getPDFAFlavour());
		}
		return flavours;
	}

	@Override
	public ValidationResult validate(PDFAParser toValidate) throws ValidationException {
		if (validators.isEmpty()) {
			return null;
		}
		validators = Collections.singletonList(validators.get(0));
		List<ValidationResult> validationResults = validateAll(toValidate);
		return validationResults.get(0);
	}

	@Override
	public List<ValidationResult> validateAll(PDFAParser toValidate) throws ValidationException {
		toValidate.setFlavours(getFlavours());
		try {
			Object root;
			modelActivity = "Building PDF model";
			try {
				root = toValidate.getRoot();
			} finally {
				modelActivity = null;
			}
			return this.validate(root);
		} catch (RuntimeException e) {
			throw new ValidationException("Caught unexpected runtime exception during validation", e);
		} catch (ModelParsingException excep) {
			throw new ValidationException("Parsing problem trying to validate.", excep);
		}
	}

	@Override
	public ComponentDetails getDetails() {
		return componentDetails;
	}

	@Override
	public String getValidationProgressString() {
		return validationProgress.getCurrentValidationJobProgressWithCommas();
	}

	@Override
	public Map<String, java.lang.Object> getValidationProgressStats() {
		var stats = validationProgress.getCurrentValidationJobStats();
		String activity = modelActivity;
		if (activity != null) stats.put("modelActivity", activity);
		return stats;
	}

	@Override
	public void cancelValidation(JobEndStatus endStatus) {
		this.jobEndStatus = endStatus;
		this.abortProcessing = true;
	}

	protected List<ValidationResult> validate(Object root) throws ValidationException {
		initialise();
		this.validationProgress.updateVariables();
		this.rootType = root.getObjectType();
		this.objectsStack.push(root);
		this.objectsContext.push(new ContextPath(null, "root", 0, null, null));

		if (root.getID() != null) {
			this.idSet.add(root.getID());
		}

		while (!isAbortProcessing() && !this.objectsStack.isEmpty()) {
			checkNext();
			this.validationProgress.incrementNumberOfProcessedObjects();
			this.validationProgress.updateNumberOfObjectsToBeProcessed(
					(int) Math.min(Integer.MAX_VALUE, objectsStack.size() + pendingIndirectObjects));
		}

		for (FlavourValidator validator : validators) {
			for (Map.Entry<Rule, List<ObjectWithContext>> entry : validator.getDeferredRules().entrySet()) {
				for (ObjectWithContext objectWithContext : entry.getValue()) {
					if (!isAbortProcessing()) {
							checkObjWithRule(validator, objectWithContext.getObject(), objectWithContext.context, entry.getKey());
					}
				}
			}			
		}

		this.validationProgress.showProgressAfterValidation();

		JavaScriptEvaluator.exitContext();

		List<ValidationResult> results = new LinkedList<>();
		for (FlavourValidator validator : validators) {
			results.add(ValidationResults.resultFromValues(validator.getProfile(), validator.results, validator.getFailedChecks(), validator.isCompliant,
					validator.testCounter, this.jobEndStatus));
		}
		return results;
	}

	private boolean isAbortProcessing() {
		if (!abortProcessing && Thread.currentThread().isInterrupted()) {
			cancelValidation(JobEndStatus.CANCELLED);
		}
		return abortProcessing;
	}

	protected void initialise() {
		this.scope = JavaScriptEvaluator.initialise();
		this.objectsStack.clear();
		this.objectsContext.clear();
		this.pendingIndirectObjects = 0;
		this.checksByType.clear();
		this.idSet.clear();
		for (FlavourValidator validator : validators) {
			validator.getFailedChecks().clear();
			validator.getDeferredRules().clear();
			validator.results.clear();
			validator.testCounter = 0;
			validator.isCompliant = true;
		}
		initializeAllVariables();
	}

	private void initializeAllVariables() {
		for (FlavourValidator flavourValidator : validators) {
			for (Variable var : flavourValidator.getProfile().getVariables()) {
				if (var == null) {
					continue;
				}

				java.lang.Object res = JavaScriptEvaluator.evaluateString(var.getDefaultValue(), this.scope);

				if (res instanceof NativeJavaObject) {
					res = ((NativeJavaObject) res).unwrap();
				}
				this.scope.put(var.getName(), this.scope, res);
			}			
		}
	}

	private void checkNext() throws ValidationException {
		java.lang.Object entry = this.objectsStack.peek();
		Object checkObject;
		ContextPath checkContext;
		if (entry instanceof IndirectBatch batch) {
			int index = batch.next;
			checkObject = batch.links.get(index);
			checkContext = new ContextPath(objectsContext.peek(), "indirectObjects", index, checkObject.getID(), null);
			batch.next = batch.accepted.nextSetBit(index + 1);
			if (batch.next < 0) {
				objectsStack.pop();
				objectsContext.pop();
			} else pendingIndirectObjects--;
		} else {
			checkObject = (Object) this.objectsStack.pop();
			checkContext = this.objectsContext.pop();
		}

		// The bundled model defines the inheritance list by type. Keep the
		// plan local to this traversal because its profiles can change.
		String type = checkObject.getObjectType();
		ObjectChecks checks;
		if (TYPE_BASED_INHERITANCE.get(checkObject.getClass())) {
			checks = checksByType.get(type);
			if (checks == null) {
				checks = objectChecks(type, checkObject.getSuperTypes());
				checksByType.put(type, checks);
			}
		} else {
			checks = objectChecks(type, checkObject.getSuperTypes());
		}
		for (RuleCheck check : checks.rules()) {
			firstProcessObjectWithRule(check.validator(), checkObject, checkContext, check.rule());
		}
		for (Variable var : checks.variables()) {
			java.lang.Object value = JavaScriptEvaluator.evalVariableResult(var, checkObject, this.scope);
			this.scope.put(var.getName(), this.scope, value);
		}

		addAllLinkedObjects(checkObject, checkContext);
	}

	private ObjectChecks objectChecks(String type, List<String> superTypes) {
		List<RuleCheck> rules = new ArrayList<>();
		List<Variable> variables = new ArrayList<>();
		List<String> types = new ArrayList<>(superTypes.size() + 1);
		types.add(type);
		types.addAll(superTypes);
		// Rules are ordered by profile, then type. Variables are ordered by
		// type, then profile. Preserve duplicates and the scope update order.
		for (FlavourValidator flavourValidator : validators) {
			for (String checkType : types) {
				Set<Rule> applicable = flavourValidator.getProfile().getRulesByObject(checkType);
				if (applicable != null) {
					for (Rule rule : applicable) {
						if (rule != null) rules.add(new RuleCheck(flavourValidator, rule));
					}
				}
			}
		}
		for (String checkType : types) {
			for (FlavourValidator flavourValidator : validators) {
				for (Variable var : flavourValidator.getProfile().getVariablesByObject(checkType)) {
					if (var != null) variables.add(var);
				}
			}
		}
		return new ObjectChecks(rules.toArray(RuleCheck[]::new), variables.toArray(Variable[]::new));
	}

	private void addAllLinkedObjects(Object checkObject, ContextPath checkContext)
			throws ValidationException {
		List<String> links = checkObject.getLinks();
		for (int j = links.size() - 1; j >= 0; --j) {
			String link = links.get(j);

			if (link == null) {
				throw new ValidationException("There is a null link name in an object. Context: " + checkContext);
			}
			if ("indirectObjects".equals(link) && checkObject.getClass() == GFCosDocument.class) {
				addIndirectObjects((GFCosDocument) checkObject, checkContext);
				continue;
			}
			List<? extends Object> objects;
			if ("XMPPackage".equals(link)) {
				modelActivity = "Building XMP package";
				try {
					objects = checkObject.getLinkedObjects(link);
				} finally {
					modelActivity = null;
				}
			} else {
				objects = checkObject.getLinkedObjects(link);
			}
			if (objects == null) {
				throw new ValidationException("There is a null link in an object. Context: " + checkContext);
			}

			for (int i = objects.size() - 1; i >= 0; --i) {
				Object obj = objects.get(i);

				if (obj == null) {
					throw new ValidationException("There is a null link in an object. Context of the link: "
							+ checkContext + "/" + link + "[" + i + "]");
				}

				if (checkRequired(obj)) {
					this.objectsStack.push(obj);
					String id = null, extra = null;

					if (obj.getID() != null) {
						id = String.valueOf(obj.getID());

						this.idSet.add(obj.getID());
					}

					if (obj.getExtraContext() != null) {
						extra = String.valueOf(obj.getExtraContext());
					}

					this.objectsContext.push(new ContextPath(checkContext, link, i, id, extra));
				}
			}
		}
	}

	private void addIndirectObjects(GFCosDocument document, ContextPath context) {
		var indirect = new IndirectObjectLinks(document);
		var accepted = new BitSet(indirect.size());
		// Reserve IDs in the same reverse order as the ordinary frontier. Nested links
		// must already see all sibling IDs as visited when the first child runs.
		for (int i = indirect.size() - 1; i >= 0; i--) {
			if (idSet.add(indirect.id(i))) accepted.set(i);
		}
		int count = accepted.cardinality();
		if (count == 0) return;
		objectsStack.push(new IndirectBatch(indirect, accepted));
		objectsContext.push(context);
		pendingIndirectObjects += count - 1L;
	}

	private boolean checkRequired(Object obj) {
		return obj.getID() == null || !this.idSet.contains(obj.getID());
	}

	public boolean firstProcessObjectWithRule(FlavourValidator flavourValidator, Object checkObject, String checkContext, Rule rule) {
		return firstProcessObjectWithRule(flavourValidator, checkObject, () -> checkContext, rule);
	}

	private boolean firstProcessObjectWithRule(FlavourValidator flavourValidator, Object checkObject, Supplier<String> checkContext, Rule rule) {
		Boolean deferred = rule.getDeferred();
		if (deferred != null && deferred) {
			List<BaseValidator.ObjectWithContext> list = flavourValidator.getDeferredRules().computeIfAbsent(rule, k -> new ArrayList<>());
			list.add(new BaseValidator.ObjectWithContext(deferredCheckObject(checkObject, rule), checkContext));
			return true;
		}
		return checkObjWithRule(flavourValidator, checkObject, checkContext, rule);
	}


	// Local change: preserve deferred evaluation, without retaining every parsed
	// drawing operator for checks that only read the live resource-name sets.
	static Object deferredCheckObject(Object object, Rule rule) {
		if (object instanceof org.verapdf.gf.model.impl.pd.GFPDContentStream stream
				&& ("inheritedResourceNames == ''".equals(rule.getTest())
					|| "undefinedResourceNames == ''".equals(rule.getTest()))
				&& rule.getError() != null && rule.getError().getArguments() != null
				&& rule.getError().getArguments().stream().allMatch(argument ->
					"inheritedResourceNames".equals(argument.getArgument())
						|| "undefinedResourceNames".equals(argument.getArgument()))) {
			return stream.resourceCheckView();
		}
		return object;
	}

	private boolean checkObjWithRule(FlavourValidator flavourValidator, Object obj, Supplier<String> contextForRule, Rule rule) {
		boolean testEvalResult = JavaScriptEvaluator.getTestEvalResult(obj, rule, this.scope);

		this.processAssertionResult(flavourValidator, testEvalResult, contextForRule, rule, obj);

		this.validationProgress.updateNumberOfFailedChecks(flavourValidator.getFailedChecks().size());
		this.validationProgress.incrementNumberOfChecks();

		return testEvalResult;
	}

	protected void processAssertionResult(FlavourValidator flavourValidator, final boolean assertionResult, final String locationContext,
										  final Rule rule, final Object obj) {
		processAssertionResult(flavourValidator, assertionResult, () -> locationContext, rule, obj);
	}

	private void processAssertionResult(FlavourValidator flavourValidator, boolean assertionResult, Supplier<String> locationContext,
										Rule rule, Object obj) {
		if (!isAbortProcessing()) {
			flavourValidator.testCounter++;
			if (flavourValidator.isCompliant) {
				flavourValidator.isCompliant = assertionResult;
			}
			if (!assertionResult) {
				int failedChecksNumberOfRule = flavourValidator.getFailedChecks().getOrDefault(rule.getRuleId(), 0);
				flavourValidator.getFailedChecks().put(rule.getRuleId(), ++failedChecksNumberOfRule);
				if ((failedChecksNumberOfRule <= maxNumberOfDisplayedFailedChecks || maxNumberOfDisplayedFailedChecks == -1) &&
						(flavourValidator.results.size() <= MAX_CHECKS_NUMBER || failedChecksNumberOfRule <= 1)) {
					Location location = ValidationResults.locationFromValues(this.rootType, locationContext.get());
					List<ErrorArgument> errorArguments = showErrorMessages ?
							JavaScriptEvaluator.getErrorArgumentsResult(obj, rule.getError().getArguments(),
									this.scope) : Collections.emptyList();
					String errorMessage = showErrorMessages ? createErrorMessage(rule.getError().getMessage(), errorArguments) : null;
					TestAssertion assertion = ValidationResults.assertionFromValues(flavourValidator.testCounter, rule.getRuleId(),
							Status.FAILED, rule.getDescription(), location, obj.getContext(), errorMessage, errorArguments);
					flavourValidator.results.add(assertion);
				}
			} else if (this.logPassedChecks && flavourValidator.results.size() <= MAX_CHECKS_NUMBER) {
				Location location = ValidationResults.locationFromValues(this.rootType, locationContext.get());
				TestAssertion assertion = ValidationResults.assertionFromValues(flavourValidator.testCounter, rule.getRuleId(),
						Status.PASSED, rule.getDescription(), location, obj.getContext(), null, Collections.emptyList());
				flavourValidator.results.add(assertion);
			}
		}
	}

	private String createErrorMessage(String errorMessage, List<ErrorArgument> arguments) {
		String result = errorMessage;
		for (int i = arguments.size(); i > 0 ; --i) {
			ErrorArgument argument = arguments.get(i - 1);
			String value = argument.getArgumentValue() != null ? argument.getArgumentValue() : "null";
			result = result.replace("%" + argument.getName() + "%", value);
			result = result.replace("%" + i, value);
		}
		return result;
	}

	@Override
	public void close() {
		this.idSet.close();
	}

	public static class ObjectWithContext {
		private final Object object;
		private final Supplier<String> context;

		public ObjectWithContext(Object object, String context) {
			this(object, () -> context);
		}

		private ObjectWithContext(Object object, Supplier<String> context) {
			this.object = object;
			this.context = context;
		}

		public Object getObject() {
			return this.object;
		}

		public String getContext() {
			return this.context.get();
		}
	}
}
