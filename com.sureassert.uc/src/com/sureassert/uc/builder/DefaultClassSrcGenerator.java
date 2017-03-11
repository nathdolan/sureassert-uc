/* Copyright 2011 Nathan Dolan. All rights reserved. */

package com.sureassert.uc.builder;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;

import com.sureassert.uc.EclipseUtils;
import com.sureassert.uc.SAException;
import com.sureassert.uc.interceptor.SignatureUtils;
import com.sureassert.uc.interceptor.SourceModel;
import com.sureassert.uc.runtime.BasicUtils;
import com.sureassert.uc.runtime.PersistentDataFactory;
import com.sureassert.uc.runtime.Signature;

public class DefaultClassSrcGenerator {

	private final String superClassName;
	private final ClassLoader cl;
	private final IJavaProject javaProject;
	private final String src;
	private String generatedClassName;
	// private int sigFinalsIdx = 0;
	private int attrInsertIdx = -1;
	private final StringBuilder sigFinalsSrc = new StringBuilder();

	private final Map<String, String> resolvedTypeByTypeParam;

	public DefaultClassSrcGenerator(String superClassName, SourceModel superSourceModel, ClassLoader cl, IJavaProject javaProject) throws SrcGenerateException {

		this.superClassName = superClassName;
		this.cl = cl;
		this.javaProject = javaProject;
		resolvedTypeByTypeParam = (superSourceModel == null) ? new HashMap<String, String>() : superSourceModel.getResolvedTypeByTypeParam();
		this.src = generateSrc();
	}

	private String generateSrc() throws SrcGenerateException {

		StringBuilder src = new StringBuilder();
		try {
			Class<?> superClass = cl.loadClass(superClassName);
			generatedClassName = PersistentDataFactory.DEFAULT_GEN_PACKAGE_NAME + "." + superClassName;
			String simpleClassName = BasicUtils.getSimpleClassName(generatedClassName);

			// Write package declaration
			if (superClass.getPackage() != null) {
				src.append("package ").append(PersistentDataFactory.DEFAULT_GEN_PACKAGE_NAME).append(".").//
						append(superClass.getPackage().getName()).append(";\n\n");
			}

			// Write comment
			src.append("/* Default implementation of ").append(superClassName).append(//
					". Generated by Sureassert UC */\n\n");

			// Write class declaration
			String implOrExt = superClass.isInterface() ? " implements " : " extends ";
			src.append("public class ").append(simpleClassName).append(implOrExt).//
					append(superClassName).append(" {\n\n");

			attrInsertIdx = src.length();

			src.append("\n");

			IType superType = javaProject.findType(superClassName, new NullProgressMonitor());

			Map<Signature, IMethod> overrideMethodsBySig = new HashMap<Signature, IMethod>();

			for (IMethod method : superType.getMethods()) {
				if (method.isConstructor()) {
					// Add delegate constructor
					Set<String> methodGenericTypes = new HashSet<String>();
					String[] paramTypes = method.getParameterTypes();
					src.append("public ");
					StringBuilder methodSrc = new StringBuilder();
					methodSrc.append(simpleClassName).append("(");
					for (int i = 0; i < paramTypes.length; i++) {
						methodSrc.append(BasicUtils.getTypeNameForSrc(paramTypes[i], //
								resolvedTypeByTypeParam, methodGenericTypes)).append(" arg").append(i);
						if (i < paramTypes.length - 1)
							methodSrc.append(", ");
					}
					methodSrc.append(") { super(");
					for (int i = 0; i < paramTypes.length; i++) {
						methodSrc.append("arg").append(i);
						if (i < paramTypes.length - 1)
							methodSrc.append(", ");
					}
					methodSrc.append(") }\n");
					appendGenericTypeDecl(src, methodGenericTypes);
					src.append(methodSrc);
				} else {
					if (superType.isInterface() || Flags.isAbstract(method.getFlags())) {
						overrideMethodsBySig.put(SignatureUtils.getSignature(method, resolvedTypeByTypeParam), method);
					}
				}
			}

			// Override methods from all super-types above super-type in hierarchy
			Set<IType> ssTypes = EclipseUtils.getSuperTypes(superType, cl, false);
			for (IType ssType : ssTypes) {
				for (IMethod method : ssType.getMethods()) {
					if (!method.isConstructor()) {
						if (ssType.isInterface() || Flags.isAbstract(method.getFlags())) {
							Signature sig = SignatureUtils.getSignature(method, resolvedTypeByTypeParam, superClassName);
							if (!overrideMethodsBySig.containsKey(sig))
								overrideMethodsBySig.put(sig, method);
						}
					}
				}
			}

			for (Entry<Signature, IMethod> methodEntry : overrideMethodsBySig.entrySet()) {
				// Override abstract methods
				addMethodSrc(src, methodEntry.getValue());
			}

			// Add generated attributes
			src.insert(attrInsertIdx, sigFinalsSrc);

			// Close class declaration
			src.append("\n}");

			BasicUtils.debug("Generated class" + generatedClassName);

		} catch (Exception e) {
			throw new SrcGenerateException(e);
		}
		return src.toString();
	}

	public String getGeneratedClassName() {

		return generatedClassName;
	}

	private void appendGenericTypeDecl(StringBuilder src, Set<String> methodGenericTypes) {

		if (!methodGenericTypes.isEmpty()) {
			src.append("<");
			for (Iterator<String> i = methodGenericTypes.iterator(); i.hasNext();) {
				src.append(i.next());
				if (i.hasNext())
					src.append(",");
			}
			src.append("> ");
		}
	}

	private void addMethodSrc(StringBuilder src, IMethod method) throws JavaModelException {

		// Override abstract methods
		Set<String> methodGenericTypes = new HashSet<String>();
		String[] paramTypes = method.getParameterTypes();
		src.append("public ");
		StringBuilder methodSrc = new StringBuilder();
		String returnType = method.getReturnType();
		String returnTypeSrc = BasicUtils.getTypeNameForSrc(returnType, resolvedTypeByTypeParam, methodGenericTypes);
		methodSrc.append(returnTypeSrc).append(" ").append(method.getElementName()).append("(");
		for (int i = 0; i < paramTypes.length; i++) {
			String typeName = BasicUtils.getTypeNameForSrc(paramTypes[i], resolvedTypeByTypeParam, methodGenericTypes);
			methodSrc.append(typeName).append(" arg").append(i);
			if (i < paramTypes.length - 1)
				methodSrc.append(", ");
		}
		methodSrc.append(") ");

		String[] excTypes = method.getExceptionTypes();
		String[] excTypesSrc;
		if (excTypes != null) {
			excTypesSrc = new String[excTypes.length];
			if (excTypes.length > 0)
				methodSrc.append("throws ");
			for (int i = 0; i < excTypes.length; i++) {
				excTypesSrc[i] = BasicUtils.getTypeNameForSrc(excTypes[i], resolvedTypeByTypeParam, methodGenericTypes);
				methodSrc.append(excTypesSrc[i]);
				if (i < excTypes.length - 1)
					methodSrc.append(", ");
			}
		} else {
			excTypesSrc = new String[] {};
		}

		appendGenericTypeDecl(src, methodGenericTypes);
		src.append(methodSrc);

		// Signature methodSig = SignatureUtils.getSignature(method, resolvedTypeByTypeParam,
		// superClassName);
		// String sigFinalStr = addNewSig(methodSig.getClassName(), methodSig.getMemberName(), //
		// BasicUtils.toSrcString(methodSig.getParamClassNames()), method);
		// String methodStubSrc = getStubSrc(src, returnTypeSrc, excTypesSrc, sigFinalStr);
		// methodStubSrc = String.format(SourceModel.registerBeforeSrc, sigFinalStr) +
		// methodStubSrc;

		if (!returnTypeSrc.equals("void")) {
			src.append(" { ").append(//
					"return ").append(BasicUtils.getDefaultValueStrForType(returnType)).//
					append("; }\n");
		} else {
			src.append(" { ").append(" }\n");
		}
	}

	// private String addNewSig(String className, String memberName, String paramsStr, IMethod
	// method) {
	//
	// String finalAttr = SourceModel.SIG_FINAL_PREFIX + (sigFinalsIdx++);
	// sigFinalsSrc.append(String.format("private static final com.sureassert.uc.runtime.Signature %s=com.sureassert.uc.runtime.SignatureTableFactory.instance.getSignature(\"%s\",\"%s\",\"%s\"); ",
	// //
	// finalAttr, className, memberName, paramsStr));
	// return finalAttr + "," + SourceModel.getThisSrc(method) +
	// getParamNamesSrc(method.getNumberOfParameters());
	// }

	// private static String getParamNamesSrc(int numParams) {
	//
	// StringBuilder paramsStr = new StringBuilder();
	// for (int i = 0; i < numParams; i++) {
	// paramsStr.append(",arg" + i);
	// }
	// return paramsStr.toString();
	// }

	/*
	 * private String getStubSrc(StringBuilder src, String returnTypeSourceName, String[]
	 * excTypeSourceNames, String sigFinalStr) {
	 * 
	 * // Add stub code
	 * String trySrc = SourceModel.getTrySrc(excTypeSourceNames);
	 * String catchSrc = SourceModel.getCatchSrc(excTypeSourceNames);
	 * if (returnTypeSourceName.equals("void")) {
	 * return String.format(SourceModel.methodStubBeforeSrcVoid, sigFinalStr, trySrc, sigFinalStr,
	 * catchSrc);
	 * } else {
	 * String parseStrSrc = null;
	 * if (returnTypeSourceName.equals("boolean")) {
	 * parseStrSrc = "Boolean.parseBoolean";
	 * } else if (returnTypeSourceName.equals("byte")) {
	 * parseStrSrc = "Byte.parseByte";
	 * } else if (returnTypeSourceName.equals("char")) {
	 * parseStrSrc = "(char) Integer.parseInt";
	 * } else if (returnTypeSourceName.equals("double")) {
	 * parseStrSrc = "Double.parseDouble";
	 * } else if (returnTypeSourceName.equals("float")) {
	 * parseStrSrc = "Float.parseFloat";
	 * } else if (returnTypeSourceName.equals("int")) {
	 * parseStrSrc = "Integer.parseInt";
	 * } else if (returnTypeSourceName.equals("long")) {
	 * parseStrSrc = "Long.parseLong";
	 * } else if (returnTypeSourceName.equals("short")) {
	 * parseStrSrc = "Short.parseShort";
	 * }
	 * if (parseStrSrc != null)
	 * return String.format(SourceModel.methodStubBeforeSrcPrimitive, sigFinalStr, trySrc,
	 * parseStrSrc, sigFinalStr, catchSrc);
	 * else {
	 * return String.format(SourceModel.methodStubBeforeSrc, sigFinalStr, trySrc,
	 * returnTypeSourceName, sigFinalStr, catchSrc);
	 * }
	 * }
	 * }
	 */

	/**
	 * Writes the generated source file to the given java source root directory.
	 * All required package directories are created.
	 * 
	 * @param srcRootDir
	 * @return the absolute path to the generated java source file
	 * @throws IOException
	 */
	public String writeSrc(File srcRootDir) throws IOException {

		srcRootDir.mkdirs();
		String[] packageNames = (PersistentDataFactory.DEFAULT_GEN_PACKAGE_NAME + "." + superClassName).split("\\.");
		File packageDir = srcRootDir;
		for (int i = 0; i < packageNames.length - 1; i++) {
			packageDir = new File(packageDir, packageNames[i]);
			packageDir.mkdir();
		}
		String filename = packageNames[packageNames.length - 1] + ".java";
		File srcFile = new File(packageDir, filename);
		FileUtils.writeStringToFile(srcFile, src, "UTF-8");
		return srcFile.getAbsolutePath();
	}

	public class SrcGenerateException extends SAException {

		private static final long serialVersionUID = 1L;

		public SrcGenerateException() {

			super();
		}

		public SrcGenerateException(String message, IFile file) {

			super(message, file);
		}

		public SrcGenerateException(String message, Throwable cause) {

			super(message, cause);
		}

		public SrcGenerateException(String message) {

			super(message);
		}

		public SrcGenerateException(Throwable cause, IFile file) {

			super(cause, file);
		}

		public SrcGenerateException(Throwable cause) {

			super(cause);
		}

	}
}