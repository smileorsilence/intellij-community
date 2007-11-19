package com.intellij.lang.ant.psi.changes;

import com.intellij.lang.ant.AntSupport;
import com.intellij.lang.ant.config.AntBuildFile;
import com.intellij.lang.ant.config.AntConfiguration;
import com.intellij.lang.ant.psi.*;
import com.intellij.lang.ant.psi.impl.AntFileImpl;
import com.intellij.lang.ant.psi.impl.AntOuterProjectElement;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.pom.xml.XmlChangeVisitor;
import com.intellij.pom.xml.events.*;
import com.intellij.psi.PsiLock;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlElement;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.Alarm;

import java.awt.*;
import java.util.HashSet;
import java.util.Set;

public class AntChangeVisitor implements XmlChangeVisitor {

  private static final Set<AntBuildFile> myDirtyFiles = new HashSet<AntBuildFile>();
  private static final Alarm myAlarm = new Alarm();

  public void visitXmlAttributeSet(final XmlAttributeSet xmlAttributeSet) {
    final XmlTag tag = xmlAttributeSet.getTag();
    if (AntFileImpl.BASEDIR_ATTR.equals(xmlAttributeSet.getName())) {
      getAntFile(tag).clearCaches();
    }
    else {
      clearParentCaches(tag);
    }
  }

  public void visitDocumentChanged(final XmlDocumentChanged xmlDocumentChanged) {
    final XmlDocument doc = xmlDocumentChanged.getDocument();
    final AntFile antFile = getAntFile(doc);
    if (antFile != null) {
      antFile.clearCaches();
    }
  }

  public void visitXmlElementChanged(final XmlElementChanged xmlElementChanged) {
    clearParentCaches(xmlElementChanged.getElement());
  }

  public void visitXmlTagChildAdd(final XmlTagChildAdd xmlTagChildAdd) {
    clearParentCaches(xmlTagChildAdd.getChild());
  }

  public void visitXmlTagChildChanged(final XmlTagChildChanged xmlTagChildChanged) {
    clearParentCaches(xmlTagChildChanged.getChild());
  }

  public void visitXmlTagChildRemoved(final XmlTagChildRemoved xmlTagChildRemoved) {
    clearParentCaches(xmlTagChildRemoved.getTag());
  }

  public void visitXmlTagNameChanged(final XmlTagNameChanged xmlTagNameChanged) {
    clearParentCaches(xmlTagNameChanged.getTag());
  }

  public void visitXmlTextChanged(final XmlTextChanged xmlTextChanged) {
    clearParentCaches(xmlTextChanged.getText());
  }

  private static void clearParentCaches(final XmlElement el) {
    final TextRange textRange = el.getTextRange();
    final AntFile file = getAntFile(el);
    if (file == null) {
      return;
    }
    AntElement element = file.lightFindElementAt(textRange.getStartOffset());
    final boolean shouldInvalidateProperties = element instanceof AntProperty;
    if (element instanceof AntTypeDef) {
      ((AntTypeDef)element).clearClassesCache();
    }
    if (element instanceof AntMacroDef || element instanceof AntPresetDef) {
      element.clearCaches();
    }
    if (element != null) {
      do{
        element = element.getAntParent();
      }
      while (element != null && !(element instanceof AntFile) && (!element.isValid() || element.getTextLength() <= textRange.getLength() || element instanceof AntOuterProjectElement));
    }

    if (element == null) {
      element = file;
    }
    synchronized (PsiLock.LOCK) {
      element.clearCaches();
      if (shouldInvalidateProperties) {
        file.invalidateProperties();
      }
      final AntMacroDef macrodef = PsiTreeUtil.getParentOfType(element, AntMacroDef.class, true, true);
      if (macrodef != null) {
        macrodef.clearCaches();
      }
      final AntPresetDef presetdef = PsiTreeUtil.getParentOfType(element, AntPresetDef.class, true, true);
      if (presetdef != null) {
        presetdef.clearCaches();
      }
      final AntTypeDef typeDef = PsiTreeUtil.getParentOfType(element, AntTypeDef.class, true, true);
      if (typeDef != null) {
        typeDef.clearCaches();
      }
    }
    updateBuildFile(file);
  }

  public static void updateBuildFile(final AntFile file) {
    if (!EventQueue.isDispatchThread()) {
      EventQueue.invokeLater(new Runnable() {
        public void run() {
          updateBuildFile(file);
        }
      });
    }
    else {
      final Project project = file.getProject();
      if (project.isDisposed()) {
        return;
      }
      for (final AntBuildFile buildFile : AntConfiguration.getInstance(project).getBuildFiles()) {
        if (file.equals(buildFile.getAntFile())) {
          synchronized (myDirtyFiles) {
            myDirtyFiles.add(buildFile);
          }
          myAlarm.cancelAllRequests();
          myAlarm.addRequest(new Runnable() {
            public void run() {
              AntBuildFile[] files = null;
              synchronized (myDirtyFiles) {
                if (myDirtyFiles.size() > 0) {
                  files = myDirtyFiles.toArray(new AntBuildFile[myDirtyFiles.size()]);
                  myDirtyFiles.clear();
                }
              }
              if (files != null) {
                for (final AntBuildFile dirtyFile : files) {
                  final Project proj = dirtyFile.getProject();
                  if (!proj.isDisposed()) {
                    AntConfiguration.getInstance(project).updateBuildFile(dirtyFile);
                  }
                }
              }
            }
          }, 300);
          break;
        }
      }
    }
  }

  private static AntFile getAntFile(final XmlElement el) {
    return AntSupport.getAntFile(el.getContainingFile());
  }
}
