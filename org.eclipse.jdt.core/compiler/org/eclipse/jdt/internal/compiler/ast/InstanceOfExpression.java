/*******************************************************************************
 * Copyright (c) 2000, 2020 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * This is an implementation of an early-draft specification developed under the Java
 * Community Process (JCP) and is made available for testing and evaluation purposes
 * only. The code is not compatible with any specification of the JCP.
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Stephan Herrmann - Contribution for
 *								bug 383368 - [compiler][null] syntactic null analysis for field references
 *								Bug 392238 - [1.8][compiler][null] Detect semantically invalid null type annotations
 *								Bug 416307 - [1.8][compiler][null] subclass with type parameter substitution confuses null checking
 *								Bug 392099 - [1.8][compiler][null] Apply null annotation on types for null analysis
 *								Bug 417295 - [1.8[[null] Massage type annotated null analysis to gel well with deep encoded type bindings.
 *								Bug 392238 - [1.8][compiler][null] Detect semantically invalid null type annotations
 *								Bug 435570 - [1.8][null] @NonNullByDefault illegally tries to affect "throws E"
 *								Bug 466713 - Null Annotations: NullPointerException using <int @Nullable []> as Type Param
 *        Andy Clement - Contributions for
 *                          Bug 383624 - [1.8][compiler] Revive code generation support for type annotations (from Olivier's work)
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.ast;

import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.ast.TypeReference.AnnotationPosition;
import org.eclipse.jdt.internal.compiler.codegen.*;
import org.eclipse.jdt.internal.compiler.flow.*;
import org.eclipse.jdt.internal.compiler.impl.Constant;
import org.eclipse.jdt.internal.compiler.lookup.*;

public class InstanceOfExpression extends OperatorExpression {

	public Expression expression;
	public TypeReference type;
	public LocalDeclaration elementVariable;
	boolean isInitialized;

public InstanceOfExpression(Expression expression, TypeReference type) {
	this.expression = expression;
	this.type = type;
	type.bits |= IgnoreRawTypeCheck; // https://bugs.eclipse.org/bugs/show_bug.cgi?id=282141
	this.bits |= INSTANCEOF << OperatorSHIFT;
	this.sourceStart = expression.sourceStart;
	this.sourceEnd = type.sourceEnd;
}
public InstanceOfExpression(Expression expression, LocalDeclaration local) {
	this.expression = expression;
	this.elementVariable = local;
	this.type = this.elementVariable.type;
	this.bits |= INSTANCEOF << OperatorSHIFT;
	this.elementVariable.sourceStart = local.sourceStart;
	this.elementVariable.sourceEnd = local.sourceEnd;
	this.sourceStart = expression.sourceStart;
	this.sourceEnd = local.declarationSourceEnd;
}

@Override
public FlowInfo analyseCode(BlockScope currentScope, FlowContext flowContext, FlowInfo flowInfo) {
	if (this.elementVariable != null) {
		flowInfo.markAsDefinitelyAssigned(this.elementVariable.binding);
	}
	LocalVariableBinding local = this.expression.localVariableBinding();
	if (local != null && (local.type.tagBits & TagBits.IsBaseType) == 0) {
		flowInfo = this.expression.analyseCode(currentScope, flowContext, flowInfo).
			unconditionalInits();
		FlowInfo initsWhenTrue = flowInfo.copy();
		initsWhenTrue.markAsComparedEqualToNonNull(local);
		flowContext.recordUsingNullReference(currentScope, local,
				this.expression, FlowContext.CAN_ONLY_NULL | FlowContext.IN_INSTANCEOF, flowInfo);
		// no impact upon enclosing try context
		return FlowInfo.conditional(initsWhenTrue, flowInfo.copy());
	}
	if (this.expression instanceof Reference && currentScope.compilerOptions().enableSyntacticNullAnalysisForFields) {
		FieldBinding field = ((Reference)this.expression).lastFieldBinding();
		if (field != null && (field.type.tagBits & TagBits.IsBaseType) == 0) {
			flowContext.recordNullCheckedFieldReference((Reference) this.expression, 1);
		}
	}
	return this.expression.analyseCode(currentScope, flowContext, flowInfo).
			unconditionalInits();
}
@Override
public void generateOptimizedBoolean(BlockScope currentScope, CodeStream codeStream, BranchLabel trueLabel, BranchLabel falseLabel, boolean valueRequired) {
	super.generateOptimizedBoolean(currentScope, codeStream, trueLabel, falseLabel, valueRequired);
	generatePatternVariable(currentScope, codeStream);
//	if (this.elementVariable != null) {
//		int position = codeStream.position;
//		codeStream.load(this.expression.localVariableBinding());
//		codeStream.checkcast(this.type, this.type.resolvedType, position);
//		codeStream.store(this.elementVariable.binding, false);
//		codeStream.recordPositionsFrom(position, this.sourceEnd);
//	}
}
@Override
public void generatePatternVariable(BlockScope currentScope, CodeStream codeStream) {
//	if (this.elementVariable != null) {
//		int position = codeStream.position;
//		codeStream.load(this.expression.localVariableBinding());
//		codeStream.checkcast(this.type, this.type.resolvedType, position);
//		codeStream.store(this.elementVariable.binding, false);
//		codeStream.recordPositionsFrom(position, this.sourceEnd);
//	}
}
/**
 * Code generation for instanceOfExpression
 *
 * @param currentScope org.eclipse.jdt.internal.compiler.lookup.BlockScope
 * @param codeStream org.eclipse.jdt.internal.compiler.codegen.CodeStream
 * @param valueRequired boolean
*/
@Override
public void generateCode(BlockScope currentScope, CodeStream codeStream, boolean valueRequired) {
	initializePatternVariables(currentScope, codeStream);
	
	int pc = codeStream.position;
	this.expression.generateCode(currentScope, codeStream, true);
	codeStream.instance_of(this.type, this.type.resolvedType);
	if (this.elementVariable != null) {
		BranchLabel actionLabel = new BranchLabel(codeStream);
		codeStream.dup();
		codeStream.ifeq(actionLabel);
		// Kludge
		if (this.expression instanceof ArrayReference) {
			ArrayReference array = (ArrayReference) this.expression;
			codeStream.load(array.receiver.localVariableBinding());
			array.position.generateCode(currentScope, codeStream, true);
			codeStream.arrayAt(array.resolvedType.id);
		} else {
			codeStream.load(this.expression.localVariableBinding());
		}
		codeStream.checkcast(this.type, this.type.resolvedType, codeStream.position);
		codeStream.store(this.elementVariable.binding, false);
		codeStream.recordPositionsFrom(codeStream.position, this.sourceEnd);
		actionLabel.place();
	}
	if (valueRequired) {
		codeStream.generateImplicitConversion(this.implicitConversion);
	} else {
		codeStream.pop();
	}
	codeStream.recordPositionsFrom(pc, this.sourceStart);
}

@Override
public StringBuffer printExpressionNoParenthesis(int indent, StringBuffer output) {
	this.expression.printExpression(indent, output).append(" instanceof "); //$NON-NLS-1$
	return this.elementVariable == null ? this.type.print(0, output) : this.elementVariable.printAsExpression(0, output);
}

@Override
public void initializePatternVariables(BlockScope currentScope, CodeStream codeStream) {
	if (this.elementVariable != null) {
		if(!this.isInitialized) {
			this.isInitialized = true;
			codeStream.aconst_null();
			codeStream.store(this.elementVariable.binding, false);
//			int position = codeStream.position;
//			codeStream.addVisibleLocalVariable(this.elementVariable.binding);
//			this.elementVariable.binding.recordInitializationStartPC(position);
		}
		int position = codeStream.position;
		codeStream.addVisibleLocalVariable(this.elementVariable.binding);
		this.elementVariable.binding.recordInitializationStartPC(position);
	}
	//generatePatternVariable(currentScope, codeStream);
}
@Override
public void resolvePatternVariable(BlockScope scope, boolean inScope) {
	if (!inScope) return;
	if (this.elementVariable != null && this.elementVariable.binding == null) {
		this.patternScope = scope;
		this.elementVariable.resolve(scope);
		// Why cant this be done in the constructor?
		this.type = this.elementVariable.type;
	}
}
@Override
public TypeBinding resolveType(BlockScope scope) {
//	resolvePatternVariable(scope, scope);
	this.constant = Constant.NotAConstant;
	if (this.type == null && this.elementVariable != null) {
		this.type = this.elementVariable.type;
	}
	TypeBinding checkedType = this.type.resolveType(scope, true /* check bounds*/);
	if (this.expression instanceof CastExpression) {
		((CastExpression) this.expression).setInstanceofType(checkedType); // for cast expression we need to know instanceof type to not tag unnecessary when needed
	}
	TypeBinding expressionType = this.expression.resolveType(scope);
	if (expressionType != null && checkedType != null && this.type.hasNullTypeAnnotation(AnnotationPosition.ANY)) {
		// don't complain if the entire operation is redundant anyway
		if (!expressionType.isCompatibleWith(checkedType) || NullAnnotationMatching.analyse(checkedType, expressionType, -1).isAnyMismatch())
			scope.problemReporter().nullAnnotationUnsupportedLocation(this.type);
	}
	if (expressionType == null || checkedType == null)
		return null;

	if (!checkedType.isReifiable()) {
		scope.problemReporter().illegalInstanceOfGenericType(checkedType, this);
	} else if (checkedType.isValidBinding()) {
		// if not a valid binding, an error has already been reported for unresolved type
		if ((expressionType != TypeBinding.NULL && expressionType.isBaseType()) // disallow autoboxing
				|| !checkCastTypesCompatibility(scope, checkedType, expressionType, null)) {
			scope.problemReporter().notCompatibleTypesError(this, expressionType, checkedType);
		}
	}
	return this.resolvedType = TypeBinding.BOOLEAN;
}

/**
 * @see org.eclipse.jdt.internal.compiler.ast.Expression#tagAsUnnecessaryCast(Scope,TypeBinding)
 */
@Override
public void tagAsUnnecessaryCast(Scope scope, TypeBinding castType) {
	// null is not instanceof Type, recognize direct scenario
	if (this.expression.resolvedType != TypeBinding.NULL)
		scope.problemReporter().unnecessaryInstanceof(this, castType);
}

@Override
public void traverse(ASTVisitor visitor, BlockScope scope) {
	if (visitor.visit(this, scope)) {
		this.expression.traverse(visitor, scope);
		if (this.elementVariable != null) {
			this.elementVariable.traverse(visitor, scope);
		} else {
			this.type.traverse(visitor, scope);
		}
	}
	visitor.endVisit(this, scope);
}
}
