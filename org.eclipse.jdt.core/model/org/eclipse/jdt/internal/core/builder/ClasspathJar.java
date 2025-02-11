/*******************************************************************************
 * Copyright (c) 2000, 2019 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Tal Lev-Ami - added package cache for zip files
 *     Stephan Herrmann - Contribution for
 *								Bug 440477 - [null] Infrastructure for feeding external annotations into compilation
 *******************************************************************************/
package org.eclipse.jdt.internal.core.builder;

import static org.eclipse.jdt.internal.core.JavaModelManager.trace;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException;
import org.eclipse.jdt.internal.compiler.classfmt.ExternalAnnotationDecorator;
import org.eclipse.jdt.internal.compiler.classfmt.ExternalAnnotationProvider;
import org.eclipse.jdt.internal.compiler.env.AccessRuleSet;
import org.eclipse.jdt.internal.compiler.env.IBinaryType;
import org.eclipse.jdt.internal.compiler.env.IModule;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.eclipse.jdt.internal.compiler.lookup.TypeConstants;
import org.eclipse.jdt.internal.compiler.util.SimpleSet;
import org.eclipse.jdt.internal.compiler.util.SuffixConstants;
import org.eclipse.jdt.internal.core.JavaModelManager;
import org.eclipse.jdt.internal.core.util.Util;

@SuppressWarnings("rawtypes")
public class ClasspathJar extends ClasspathLocation {
final boolean isOnModulePath;

static class PackageCacheEntry {
	WeakReference<ZipFile> zipFile;
	long lastModified;
	long fileSize;
	SimpleSet packageSet;

	PackageCacheEntry(ZipFile zipFile, long lastModified, long fileSize, SimpleSet packageSet) {
		this.zipFile = new WeakReference<>(zipFile);
		this.lastModified = lastModified;
		this.fileSize = fileSize;
		this.packageSet = packageSet;
	}
}

protected static Map<String, PackageCacheEntry> PackageCache = new ConcurrentHashMap<>();

protected static void addToPackageSet(SimpleSet packageSet, String fileName, boolean endsWithSep) {
	int last = endsWithSep ? fileName.length() : fileName.lastIndexOf('/');
	while (last > 0) {
		// extract the package name
		String packageName = fileName.substring(0, last);
		if (packageSet.addIfNotIncluded(packageName) == null)
			return; // already existed
		last = packageName.lastIndexOf('/');
	}
}

/**
 * Calculate and cache the package list available in the zipFile.
 * @return A SimpleSet with the all the package names in the zipFile.
 */
protected SimpleSet findPackageSet() {
	PackageCacheEntry entry = PackageCache.compute(this.zipFilename, (zipFileName, cacheEntry) -> {
		if(cacheEntry != null && cacheEntry.zipFile.get() == this.zipFile) {
			return cacheEntry;
		}
		long timestamp = this.lastModified();
		if (cacheEntry != null && cacheEntry.lastModified == timestamp && cacheEntry.fileSize == this.fileSize) {
			cacheEntry.zipFile = new WeakReference<>(this.zipFile);
			return cacheEntry;
		}
		final SimpleSet packageSet = new SimpleSet(41);
		packageSet.add(""); //$NON-NLS-1$
		readJarContent(packageSet);
		return new PackageCacheEntry(this.zipFile, timestamp, this.fileSize, packageSet);
	});

	return entry.packageSet;
}
protected String readJarContent(final SimpleSet packageSet) {
	String modInfo = null;
	for (Enumeration e = this.zipFile.entries(); e.hasMoreElements(); ) {
		String fileName = ((ZipEntry) e.nextElement()).getName();
		if (fileName.startsWith("META-INF/")) //$NON-NLS-1$
			continue;
		if (modInfo == null) {
			int folderEnd = fileName.lastIndexOf('/');
			folderEnd += 1;
			String className = fileName.substring(folderEnd, fileName.length());
			if (className.equalsIgnoreCase(IModule.MODULE_INFO_CLASS)) {
				modInfo = fileName;
			}
		}
		addToPackageSet(packageSet, fileName, false);
	}
	return modInfo;
}
IModule initializeModule() {
	IModule mod = null;
	try (ZipFile file = new ZipFile(this.zipFilename)) {
		String releasePath = "META-INF/versions/" + this.compliance + '/' + IModule.MODULE_INFO_CLASS; //$NON-NLS-1$
		ClassFileReader classfile = null;
		try {
			classfile = ClassFileReader.read(file, releasePath);
		} catch (Exception e) {
			if (JavaModelManager.VERBOSE) {
				trace("", e); //$NON-NLS-1$
			}
			// move on to the default
		}
		if (classfile == null) {
			classfile = ClassFileReader.read(file, IModule.MODULE_INFO_CLASS); // FIXME: use jar cache
		}
		if (classfile != null) {
			mod = classfile.getModuleDeclaration();
		}
	} catch (ClassFormatException | IOException e) {
		// do nothing
	}
	return mod;
}

String zipFilename; // keep for equals
IFile resource;
ZipFile zipFile;
long lastModified;
long fileSize;
boolean closeZipFileAtEnd;
private SimpleSet knownPackageNames;
// Meant for ClasspathMultiReleaseJar, not used in here
String compliance;

ClasspathJar(IFile resource, AccessRuleSet accessRuleSet, IPath externalAnnotationPath, boolean isOnModulePath) {
	this.resource = resource;
	try {
		java.net.URI location = resource.getLocationURI();
		if (location == null) {
			this.zipFilename = ""; //$NON-NLS-1$
		} else {
			File localFile = Util.toLocalFile(location, null);
			this.zipFilename = localFile.getPath();
		}
	} catch (CoreException e) {
		// ignore
		this.zipFilename = ""; //$NON-NLS-1$
	}
	this.zipFile = null;
	this.knownPackageNames = null;
	this.accessRuleSet = accessRuleSet;
	if (externalAnnotationPath != null)
		this.externalAnnotationPath = externalAnnotationPath.toString();
	this.isOnModulePath = isOnModulePath;
}

ClasspathJar(String zipFilename, long lastModified, AccessRuleSet accessRuleSet, IPath externalAnnotationPath, boolean isOnModulePath) {
	this.zipFilename = zipFilename;
	this.lastModified = lastModified;
	this.zipFile = null;
	this.knownPackageNames = null;
	this.accessRuleSet = accessRuleSet;
	if (externalAnnotationPath != null)
		this.externalAnnotationPath = externalAnnotationPath.toString();
	this.isOnModulePath = isOnModulePath;
}

public ClasspathJar(ZipFile zipFile, AccessRuleSet accessRuleSet, boolean isOnModulePath) {
	this(zipFile.getName(), 0, accessRuleSet, null, isOnModulePath);
	this.zipFile = zipFile;
	this.closeZipFileAtEnd = true;
}

@Override
public void cleanup() {
	if (this.closeZipFileAtEnd) {
		if (this.zipFile != null) {
			try {
				this.zipFile.close();
				if (JavaModelManager.ZIP_ACCESS_VERBOSE) {
					trace("(" + Thread.currentThread() + ") [ClasspathJar.cleanup()] Closed ZipFile on " + this.zipFilename); //$NON-NLS-1$	//$NON-NLS-2$
				}
			} catch(IOException e) { // ignore it
				JavaCore.getPlugin().getLog().log(new Status(IStatus.ERROR, JavaCore.PLUGIN_ID, "Error closing " + this.zipFile.getName(), e)); //$NON-NLS-1$
			}
			this.zipFile = null;
		}
		if (this.annotationZipFile != null) {
			try {
				this.annotationZipFile.close();
				if (JavaModelManager.ZIP_ACCESS_VERBOSE) {
					trace("(" + Thread.currentThread() + ") [ClasspathJar.cleanup()] Closed Annotation ZipFile on " + this.zipFilename); //$NON-NLS-1$	//$NON-NLS-2$
				}
			} catch(IOException e) { // ignore it
				JavaCore.getPlugin().getLog().log(new Status(IStatus.ERROR, JavaCore.PLUGIN_ID, "Error closing " + this.annotationZipFile.getName(), e)); //$NON-NLS-1$
			}
			this.annotationZipFile = null;
		}
	} else {
		if (this.zipFile != null && JavaModelManager.ZIP_ACCESS_VERBOSE) {
			try {
				this.zipFile.size();
				trace("(" + Thread.currentThread() + ") [ClasspathJar.cleanup()] ZipFile NOT closed on " + this.zipFilename); //$NON-NLS-1$	//$NON-NLS-2$
			} catch (IllegalStateException e) {
				// OK: the file was already closed
			}
		}
	}
	this.module = null; // TODO(SHMOD): is this safe?
	this.knownPackageNames = null;
}

@Override
public boolean equals(Object o) {
	if (this == o) return true;
	if (!(o instanceof ClasspathJar)) return false;
	ClasspathJar jar = (ClasspathJar) o;
	if (this.accessRuleSet != jar.accessRuleSet)
		if (this.accessRuleSet == null || !this.accessRuleSet.equals(jar.accessRuleSet))
			return false;
	if (!Util.equalOrNull(this.compliance, jar.compliance)) {
		return false;
	}
	return this.zipFilename.equals(jar.zipFilename)
			&& lastModified() == jar.lastModified()
			&& this.isOnModulePath == jar.isOnModulePath
			&& areAllModuleOptionsEqual(jar);
}

@Override
public NameEnvironmentAnswer findClass(String binaryFileName, String qualifiedPackageName, String moduleName, String qualifiedBinaryFileName, boolean asBinaryOnly, Predicate<String> moduleNameFilter) {
	if (!isPackage(qualifiedPackageName, moduleName)) return null; // most common case

	try {
		IBinaryType reader = ClassFileReader.read(this.zipFile, qualifiedBinaryFileName);
		if (reader != null) {
			char[] modName = this.module == null ? null : this.module.name();
			if (reader instanceof ClassFileReader) {
				ClassFileReader classReader = (ClassFileReader) reader;
				if (classReader.moduleName == null)
					classReader.moduleName = modName;
				else
					modName = classReader.moduleName;
				}
			String fileNameWithoutExtension = qualifiedBinaryFileName.substring(0, qualifiedBinaryFileName.length() - SuffixConstants.SUFFIX_CLASS.length);
			return createAnswer(fileNameWithoutExtension, reader, modName);
		}
	} catch (IOException | ClassFormatException e) { // treat as if class file is missing
	}
	return null;
}

@Override
public IPath getProjectRelativePath() {
	if (this.resource == null) return null;
	return	this.resource.getProjectRelativePath();
}

@Override
public int hashCode() {
	return this.zipFilename == null ? super.hashCode() : this.zipFilename.hashCode();
}

@Override
public boolean isPackage(String qualifiedPackageName, String moduleName) {
	if (moduleName != null) {
		if (this.module == null || !moduleName.equals(String.valueOf(this.module.name())))
			return false;
	}
	if (this.knownPackageNames == null)
		scanContent();
	return this.knownPackageNames.includes(qualifiedPackageName);
}
@Override
public boolean hasCompilationUnit(String pkgName, String moduleName) {
	if (scanContent()) {
		if (!this.knownPackageNames.includes(pkgName)) {
			// Don't waste time walking through the zip if we know that it doesn't
			// contain a directory that matches pkgName
			return false;
		}

		// Even if knownPackageNames contained the pkg we're looking for, we still need to verify
		// that the package in this jar actually contains at least one .class file (since
		// knownPackageNames includes empty packages)
		for (Enumeration<? extends ZipEntry> e = this.zipFile.entries(); e.hasMoreElements(); ) {
			String fileName = e.nextElement().getName();
			if (fileName.startsWith(pkgName)
					&& fileName.toLowerCase().endsWith(SuffixConstants.SUFFIX_STRING_class)
					&& fileName.indexOf('/', pkgName.length()+1) == -1)
				return true;
		}
	}

	return false;
}

/** Scan the contained packages and try to locate the module descriptor. */
private boolean scanContent() {
	try {
		if (this.zipFile == null) {
			if (JavaModelManager.ZIP_ACCESS_VERBOSE) {
				trace("(" + Thread.currentThread() + ") [ClasspathJar.isPackage(String)] Creating ZipFile on " + this.zipFilename); //$NON-NLS-1$	//$NON-NLS-2$
			}
			this.zipFile = new ZipFile(this.zipFilename);
			this.closeZipFileAtEnd = true;
			this.knownPackageNames = findPackageSet();
		} else {
			this.knownPackageNames = findPackageSet();
		}
		return true;
	} catch(Exception e) {
		this.knownPackageNames = new SimpleSet(); // assume for this build the zipFile is empty
		return false;
	}
}

public long lastModified() {
	if (this.lastModified == 0) {
		long lastMod=-1;
		long size=-1;
		try {
			BasicFileAttributes attributes = Files.readAttributes(Path.of(this.zipFilename), BasicFileAttributes.class);
			lastMod = attributes.lastModifiedTime().toMillis();
			size = attributes.size();
		} catch (IOException e) {
			// ignore
		}
		this.lastModified = lastMod;
		this.fileSize = size;
	}
	return this.lastModified;
}

@Override
public String toString() {
	String start = "Classpath jar file " + this.zipFilename; //$NON-NLS-1$
	if (this.accessRuleSet == null)
		return start;
	return start + " with " + this.accessRuleSet; //$NON-NLS-1$
}

@Override
public String debugPathString() {
	long time = lastModified();
	if (time == 0)
		return this.zipFilename;
	return this.zipFilename + '(' + (new Date(time)) + " : " + time + ')'; //$NON-NLS-1$
}

@Override
public IModule getModule() {
	if (this.knownPackageNames == null)
		scanContent();
	return this.module;
}

@Override
public NameEnvironmentAnswer findClass(String typeName, String qualifiedPackageName, String moduleName, String qualifiedBinaryFileName) {
	//
	return findClass(typeName, qualifiedPackageName, moduleName, qualifiedBinaryFileName, false, null);
}
public Manifest getManifest() {
	if (!scanContent()) // ensure zipFile is initialized
		return null;
	ZipEntry entry = this.zipFile.getEntry(TypeConstants.META_INF_MANIFEST_MF);
	if (entry == null) {
		return null;
	}
	try(InputStream is = this.zipFile.getInputStream(entry)) {
		return new Manifest(is);
	} catch (IOException e) {
		// cannot use manifest
	}
	return null;
}
@Override
public char[][] listPackages() {
	if (!scanContent()) // ensure zipFile is initialized
		return null;
	char[][] result = new char[this.knownPackageNames.elementSize][];
	int count = 0;
	for (int i=0; i<this.knownPackageNames.values.length; i++) {
		String string = (String) this.knownPackageNames.values[i];
		if (string != null &&!string.isEmpty()) {
			result[count++] = string.replace('/', '.').toCharArray();
		}
	}
	if (count < result.length)
		return Arrays.copyOf(result, count);
	return result;
}

@Override
protected IBinaryType decorateWithExternalAnnotations(IBinaryType reader, String fileNameWithoutExtension) {
	if (scanContent()) { // ensure zipFile is initialized
		String qualifiedBinaryFileName = fileNameWithoutExtension + ExternalAnnotationProvider.ANNOTATION_FILE_SUFFIX;
		ZipEntry entry = this.zipFile.getEntry(qualifiedBinaryFileName);
		if (entry != null) {
			try(InputStream is = this.zipFile.getInputStream(entry)) {
				return new ExternalAnnotationDecorator(reader, new ExternalAnnotationProvider(is, fileNameWithoutExtension));
			} catch (IOException e) {
				// ignore
			}
		}
	}
	return reader; // undecorated
}
}
