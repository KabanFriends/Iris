package net.coderbot.iris.pipeline.transform;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.github.douira.glsl_transformer.ast.node.Identifier;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.basic.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.FunctionParameter;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.EmptyDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifierPart;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinNumericTypeSpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.FunctionPrototype;
import io.github.douira.glsl_transformer.ast.node.type.specifier.TypeSpecifier;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.match.AutoHintedMatcher;
import io.github.douira.glsl_transformer.ast.query.match.Matcher;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.ast.transform.Template;
import io.github.douira.glsl_transformer.util.Type;
import net.coderbot.iris.gl.shader.ShaderType;
import net.coderbot.iris.pipeline.PatchedShaderPrinter;

public class CompatibilityTransformer {
	static Logger LOGGER = LogManager.getLogger(CompatibilityTransformer.class);

	private static StorageQualifier getConstQualifier(TypeQualifier qualifier) {
		if (qualifier == null) {
			return null;
		}
		for (TypeQualifierPart constQualifier : qualifier.getChildren()) {
			if (constQualifier instanceof StorageQualifier) {
				StorageQualifier storageQualifier = (StorageQualifier) constQualifier;
				if (storageQualifier.storageType == StorageQualifier.StorageType.CONST) {
					return storageQualifier;
				}
			}
		}
		return null;
	}

	public static void transformEach(ASTParser t, TranslationUnit tree, Root root, Parameters parameters) {
		/**
		 * Removes const storage qualifier from declarations in functions if they are
		 * initialized with const parameters. Const parameters are immutable parameters
		 * and can't be used to initialize const declarations because they expect
		 * constant, not just immutable, expressions. This varies between drivers and
		 * versions. Also removes the const qualifier from declarations that use the
		 * identifiers from which the declaration was removed previously.
		 * See https://wiki.shaderlabs.org/wiki/Compiler_Behavior_Notes
		 */
		Map<FunctionDefinition, Set<String>> constFunctions = new HashMap<>();
		Set<String> processingSet = new HashSet<>();
		List<FunctionDefinition> unusedFunctions = new LinkedList<>();
		for (FunctionDefinition definition : root.nodeIndex.get(FunctionDefinition.class)) {
			// check if this function is ever used
			FunctionPrototype prototype = definition.getFunctionPrototype();
			String functionName = prototype.getName().getName();
			if (!functionName.equals("main") && root.identifierIndex.getStream(functionName).count() <= 1) {
				// remove unused functions
				// unused function removal can be helpful since some drivers don't do some
				// checks on unused functions. Additionally, sometimes bugs in unused code can
				// be avoided this way.
				// TODO: integrate into debug mode (allow user to disable this behavior for
				// debugging purposes)
				unusedFunctions.add(definition);
				if (PatchedShaderPrinter.prettyPrintShaders) {
					LOGGER.warn("Removing unused function " + functionName);
				} else if (unusedFunctions.size() == 1) {
					LOGGER.warn(
							"Removing unused function " + functionName
									+ " and omitting further such messages outside of debug mode. See debugging.md for more information.");
				}
				continue;
			}

			// stop on functions without parameters
			if (prototype.getChildren().isEmpty()) {
				continue;
			}

			// find the const parameters
			Set<String> names = new HashSet<>(prototype.getChildren().size());
			for (FunctionParameter parameter : prototype.getChildren()) {
				if (getConstQualifier(parameter.getType().getTypeQualifier()) != null) {
					String name = parameter.getName().getName();
					names.add(name);
					processingSet.add(name);
				}
			}
			if (!names.isEmpty()) {
				constFunctions.put(definition, names);
			}
		}

		// remove collected unused functions
		for (FunctionDefinition definition : unusedFunctions) {
			definition.detachAndDelete();
		}

		// find the reference expressions for the const parameters
		// and check that they are in the right function and are of the right type
		boolean constDeclarationHit = false;
		Deque<String> processingQueue = new ArrayDeque<>(processingSet);
		while (!processingQueue.isEmpty()) {
			String name = processingQueue.poll();
			processingSet.remove(name);
			for (Identifier id : root.identifierIndex.get(name)) {
				// since this searches for reference expressions, this won't accidentally find
				// the name as the name of a declaration member
				ReferenceExpression reference = id.getAncestor(ReferenceExpression.class);
				if (reference == null) {
					continue;
				}
				TypeAndInitDeclaration taid = reference.getAncestor(TypeAndInitDeclaration.class);
				if (taid == null) {
					continue;
				}
				FunctionDefinition inDefinition = taid.getAncestor(FunctionDefinition.class);
				if (inDefinition == null) {
					continue;
				}
				Set<String> constIdsInFunction = constFunctions.get(inDefinition);
				if (constIdsInFunction == null) {
					continue;
				}
				if (constIdsInFunction.contains(name)) {
					// remove the const qualifier from the reference expression
					TypeQualifier qualifier = taid.getType().getTypeQualifier();
					StorageQualifier constQualifier = getConstQualifier(qualifier);
					if (constQualifier == null) {
						continue;
					}
					constQualifier.detachAndDelete();
					if (qualifier.getChildren().isEmpty()) {
						qualifier.detachAndDelete();
					}
					constDeclarationHit = true;

					// add all members of the declaration to the list of const parameters to process
					for (DeclarationMember member : taid.getMembers()) {
						String memberName = member.getName().getName();

						// the name may not be the same as the parameter name
						if (constIdsInFunction.contains(memberName)) {
							throw new IllegalStateException("Illegal redefinition of const parameter " + name);
						}

						constIdsInFunction.add(memberName);

						// don't add to the queue twice if it's already been added by a different scope
						if (!processingSet.contains(memberName)) {
							processingQueue.add(memberName);
							processingSet.add(memberName);
						}
					}
				}
			}
		}

		if (constDeclarationHit) {
			LOGGER.warn(
					"Removed the const keyword from declarations that use const parameters. See debugging.md for more information.");
		}

		// remove empty external declarations
		boolean emptyDeclarationHit = root.process(
				root.nodeIndex.getStream(EmptyDeclaration.class),
				ASTNode::detachAndDelete);
		if (emptyDeclarationHit) {
			LOGGER.warn(
					"Removed empty external declarations (\";\").");
		}
	}

	private static class DeclarationMatcher extends AutoHintedMatcher<ExternalDeclaration> {
		private final StorageType storageType;

		public DeclarationMatcher(StorageType storageType) {
			super("out float name;", Matcher.externalDeclarationPattern);
			this.storageType = storageType;
			markClassWildcard("qualifier", pattern.getRoot().nodeIndex.getOne(TypeQualifier.class));
			markClassWildcard("type", pattern.getRoot().nodeIndex.getOne(BuiltinNumericTypeSpecifier.class));
			markClassWildcard("name*", pattern.getRoot().identifierIndex.getOne("name").getAncestor(DeclarationMember.class));
		}

		public boolean matchesSpecialized(ExternalDeclaration tree, ShaderType shaderType) {
			boolean result = super.matchesExtract(tree);
			if (!result) {
				return false;
			}
			TypeQualifier qualifier = getNodeMatch("qualifier", TypeQualifier.class);
			for (TypeQualifierPart part : qualifier.getParts()) {
				if (part instanceof StorageQualifier) {
					StorageQualifier storageQualifier = (StorageQualifier) part;
					StorageType qualifierStorageType = storageQualifier.storageType;
					// TODO: investigate the specified behavior of varying in geometry shaders and
					// the combination of varying with in or out qualifiers
					if (qualifierStorageType == storageType ||
							qualifierStorageType == StorageType.VARYING &&
									(shaderType == ShaderType.VERTEX && storageType == StorageType.OUT ||
											shaderType == ShaderType.GEOMETRY && storageType == StorageType.OUT ||
											shaderType == ShaderType.FRAGMENT && storageType == StorageType.IN)) {
						return true;
					}
				}
			}
			return false;
		}
	}

	private static final ShaderType[] pipeline = { ShaderType.VERTEX, ShaderType.GEOMETRY, ShaderType.FRAGMENT };
	private static final DeclarationMatcher outDeclarationMatcher = new DeclarationMatcher(StorageType.OUT);
	private static final DeclarationMatcher inDeclarationMatcher = new DeclarationMatcher(StorageType.IN);

	private static final String tagPrefix = "iris_template_";
	private static final Template<ExternalDeclaration> declarationTemplate = Template
			.withExternalDeclaration("out __type __name;");
	private static final Template<Statement> initTemplate = Template.withStatement("__decl = __value;");
	private static final Template<ExternalDeclaration> variableTemplate = Template
			.withExternalDeclaration("__type __internalDecl;");
	private static final Template<Statement> statementTemplate = Template
			.withStatement("__oldDecl = vec3(__internalDecl);");
	private static final Template<Statement> statementTemplateVector = Template
			.withStatement("__oldDecl = vec3(__internalDecl, vec4(0));");

	static {
		declarationTemplate.markLocalReplacement(declarationTemplate.getSourceRoot().nodeIndex.getOne(TypeQualifier.class));
		declarationTemplate.markLocalReplacement("__type", TypeSpecifier.class);
		declarationTemplate.markIdentifierReplacement("__name");
		initTemplate.markIdentifierReplacement("__decl");
		initTemplate.markLocalReplacement("__value", ReferenceExpression.class);
		variableTemplate.markLocalReplacement("__type", TypeSpecifier.class);
		variableTemplate.markIdentifierReplacement("__internalDecl");
		statementTemplate.markIdentifierReplacement("__oldDecl");
		statementTemplate.markIdentifierReplacement("__internalDecl");
		statementTemplate.markLocalReplacement(
				statementTemplate.getSourceRoot().nodeIndex.getStream(BuiltinNumericTypeSpecifier.class)
						.filter(specifier -> specifier.type == Type.F32VEC3).findAny().get());
		statementTemplateVector.markIdentifierReplacement("__oldDecl");
		statementTemplateVector.markIdentifierReplacement("__internalDecl");
		statementTemplateVector.markLocalReplacement(
				statementTemplateVector.getSourceRoot().nodeIndex.getStream(BuiltinNumericTypeSpecifier.class)
						.filter(specifier -> specifier.type == Type.F32VEC3).findAny().get());
	}

	private static Statement getInitializer(Root root, String name, Type type) {
		return initTemplate.getInstanceFor(root,
				new Identifier(name),
				type.isScalar()
						? LiteralExpression.getDefaultValue(type)
						: Root.indexNodes(root, () -> new FunctionCallExpression(
								new Identifier(type.getMostCompactName()),
								Stream.of(LiteralExpression.getDefaultValue(type)))));
	}

	private static TypeQualifier makeQualifierOut(TypeQualifier typeQualifier) {
		for (TypeQualifierPart qualifierPart : typeQualifier.getParts()) {
			if (qualifierPart instanceof StorageQualifier) {
				StorageQualifier storageQualifier = (StorageQualifier) qualifierPart;
				if (((StorageQualifier) qualifierPart).storageType == StorageType.IN) {
					storageQualifier.storageType = StorageType.OUT;
				}
			}
		}
		return typeQualifier;
	}

	// does transformations that require cross-shader type data
	public static void transformGrouped(
			ASTParser t,
			Map<PatchShaderType, TranslationUnit> trees,
			Parameters parameters) {
		/**
		 * find attributes that are declared as "in" in geometry or fragment but not
		 * declared as "out" in the previous stage. The missing "out" declarations for
		 * these attributes are added and initialized.
		 * 
		 * It doesn't bother with array specifiers because they are only legal in
		 * geometry shaders, but then also only as an in declaration. The out
		 * declaration in the vertex shader is still just a single value. Missing out
		 * declarations in the geometry shader are also just normal.
		 * 
		 * TODO:
		 * - fix issues where Iris' own declarations are detected and patched like
		 * iris_FogFragCoord if there are geometry shaders present
		 * - improved geometry shader support? They use funky declarations
		 */
		ShaderType prevType = null;
		for (int i = 0; i < pipeline.length; i++) {
			ShaderType type = pipeline[i];
			PatchShaderType[] patchTypes = PatchShaderType.fromGlShaderType(type);

			// check if the patch types have sources and continue if not
			boolean hasAny = false;
			for (PatchShaderType currentType : patchTypes) {
				if (trees.get(currentType) != null) {
					hasAny = true;
				}
			}
			if (!hasAny) {
				continue;
			}

			// if the current type has sources but the previous one doesn't, set the
			// previous one and continue
			if (prevType == null) {
				prevType = type;
				continue;
			}

			PatchShaderType prevPatchTypes = PatchShaderType.fromGlShaderType(prevType)[0];
			TranslationUnit prevTree = trees.get(prevPatchTypes);
			Root prevRoot = prevTree.getRoot();

			// test if the prefix tag is used for some reason
			if (prevRoot.identifierIndex.prefixQueryFlat(tagPrefix).findAny().isPresent()) {
				LOGGER.warn("The prefix tag " + tagPrefix + " is used in the shader, bailing compatibility transformation.");
				return;
			}

			// find out declarations
			Map<String, BuiltinNumericTypeSpecifier> outDeclarations = new HashMap<>();
			for (DeclarationExternalDeclaration declaration : prevRoot.nodeIndex.get(DeclarationExternalDeclaration.class)) {
				if (outDeclarationMatcher.matchesSpecialized(declaration, prevType)) {
					BuiltinNumericTypeSpecifier extractedType = outDeclarationMatcher.getNodeMatch("type",
							BuiltinNumericTypeSpecifier.class);
					for (DeclarationMember member : outDeclarationMatcher
							.getNodeMatch("name*", DeclarationMember.class)
							.getAncestor(TypeAndInitDeclaration.class)
							.getMembers()) {
						outDeclarations.put(member.getName().getName(), extractedType);
					}
				}
			}

			// add out declarations that are missing for in declarations
			for (PatchShaderType currentType : patchTypes) {
				TranslationUnit currentTree = trees.get(currentType);
				if (currentTree == null) {
					continue;
				}
				Root currentRoot = currentTree.getRoot();

				for (ExternalDeclaration declaration : currentRoot.nodeIndex.get(DeclarationExternalDeclaration.class)) {
					if (!inDeclarationMatcher.matchesSpecialized(declaration, currentType.glShaderType)) {
						continue;
					}

					BuiltinNumericTypeSpecifier inTypeSpecifier = inDeclarationMatcher.getNodeMatch("type",
							BuiltinNumericTypeSpecifier.class);
					for (DeclarationMember inDeclarationMember : inDeclarationMatcher
							.getNodeMatch("name*", DeclarationMember.class)
							.getAncestor(TypeAndInitDeclaration.class)
							.getMembers()) {
						String name = inDeclarationMember.getName().getName();

						// patch missing declarations with an initialization
						if (!outDeclarations.containsKey(name)) {
							// make sure the declared in is actually used
							if (!currentRoot.identifierIndex.getAncestors(name, ReferenceExpression.class).findAny().isPresent()) {
								continue;
							}

							if (inTypeSpecifier == null) {
								LOGGER.warn(
										"The in declaration '" + name + "' in the " + currentType.glShaderType.name()
												+ " shader that has a missing corresponding out declaration in the previous stage "
												+ prevType.name() + " has a non-numeric type and could not be compatibility-patched. See debugging.md for more information.");
								continue;
							}
							Type inType = inTypeSpecifier.type;

							// insert the new out declaration but copy over the type qualifiers, except for
							// the in/out qualifier
							TypeQualifier outQualifier = (TypeQualifier) inDeclarationMatcher
									.getNodeMatch("qualifier").cloneInto(prevRoot);
							makeQualifierOut(outQualifier);
							prevTree.injectNode(ASTInjectionPoint.BEFORE_DECLARATIONS, declarationTemplate.getInstanceFor(prevRoot,
									outQualifier,
									inTypeSpecifier.cloneInto(prevRoot),
									new Identifier(name)));

							// add the initializer to the main function
							prevTree.prependMain(getInitializer(prevRoot, name, inType));

							// update out declarations to prevent duplicates
							outDeclarations.put(name, null);

							LOGGER.warn(
									"The in declaration '" + name + "' in the " + currentType.glShaderType.name()
											+ " shader is missing a corresponding out declaration in the previous stage "
											+ prevType.name() + " and has been compatibility-patched. See debugging.md for more information.");
						}

						// patch mismatching declaration with a local variable and a cast
						else {
							// there is an out declaration for this in declaration, check if the types match
							BuiltinNumericTypeSpecifier outTypeSpecifier = outDeclarations.get(name);

							// skip newly inserted out declarations
							if (outTypeSpecifier == null) {
								continue;
							}

							Type inType = inTypeSpecifier.type;
							Type outType = outTypeSpecifier.type;

							// skip if the type matches, nothing has to be done
							if (inType == outType) {
								// if the types match but it's never assigned a value,
								// an initialization is added
								if (prevRoot.identifierIndex.get(name).size() > 1) {
									continue;
								}

								// add an initialization statement for this declaration
								prevTree.prependMain(getInitializer(prevRoot, name, inType));
								outDeclarations.put(name, null);
								continue;
							}

							// bail and warn on mismatching dimensionality
							if (outType.getDimension() != inType.getDimension()) {
								LOGGER.warn(
										"The in declaration '" + name + "' in the " + currentType.glShaderType.name()
												+ " shader has a mismatching dimensionality (scalar/vector/matrix) with the out declaration in the previous stage "
												+ prevType.name() + " and could not be compatibility-patched. See debugging.md for more information.");
								continue;
							}

							boolean isVector = outType.isVector();

							// rename all references of this out declaration to a new name (iris_)
							String newName = tagPrefix + name;
							prevRoot.identifierIndex.rename(name, newName);

							// rename the original out declaration back to the original name
							TypeAndInitDeclaration outDeclaration = outTypeSpecifier.getAncestor(TypeAndInitDeclaration.class);
							if (outDeclaration == null) {
								continue;
							}

							List<DeclarationMember> outMembers = outDeclaration.getMembers();
							DeclarationMember outMember = null;
							for (DeclarationMember member : outMembers) {
								if (member.getName().getName().equals(newName)) {
									outMember = member;
								}
							}
							if (outMember == null) {
								throw new IllegalStateException("The targeted out declaration member is missing!");
							}
							outMember.getName().replaceByAndDelete(new Identifier(name));

							// move the declaration member out of the declaration in case there is more than
							// one member to avoid changing the other member's type as well.
							if (outMembers.size() > 1) {
								outMember.detach();
								outTypeSpecifier = outTypeSpecifier.cloneInto(prevRoot);
								DeclarationExternalDeclaration singleOutDeclaration = (DeclarationExternalDeclaration) declarationTemplate
										.getInstanceFor(prevRoot,
												makeQualifierOut(outDeclaration.getType().getTypeQualifier().cloneInto(prevRoot)),
												outTypeSpecifier,
												new Identifier(name));
								((TypeAndInitDeclaration) singleOutDeclaration.getDeclaration()).getMembers().set(0, outMember);
								prevTree.injectNode(ASTInjectionPoint.BEFORE_DECLARATIONS, singleOutDeclaration);
							}

							// add a global variable with the new name and the old type
							prevTree.injectNode(ASTInjectionPoint.BEFORE_DECLARATIONS, variableTemplate.getInstanceFor(prevRoot,
									outTypeSpecifier.cloneInto(prevRoot),
									new Identifier(newName)));

							// insert a statement at the end of the main function that sets the value of the
							// out declaration to the value of the global variable and does a type cast
							prevTree.appendMain(
									(isVector && outType.getDimensions()[0] < inType.getDimensions()[0] ? statementTemplateVector
											: statementTemplate).getInstanceFor(prevRoot,
													new Identifier(name),
													new Identifier(newName),
													inTypeSpecifier.cloneInto(prevRoot)));

							// make the out declaration use the same type as the fragment shader
							outTypeSpecifier.replaceByAndDelete(inTypeSpecifier.cloneInto(prevRoot));

							// don't do the patch twice
							outDeclarations.put(name, null);

							LOGGER.warn(
									"The out declaration '" + name + "' in the " + prevType.name()
											+ " shader has a different type " + outType.getMostCompactName()
											+ " than the corresponding in declaration of type " + inType.getMostCompactName()
											+ " in the following stage " + currentType.glShaderType.name()
											+ " and has been compatibility-patched. See debugging.md for more information.");
						}
					}
				}
			}

			prevType = type;
		}
	}
}
