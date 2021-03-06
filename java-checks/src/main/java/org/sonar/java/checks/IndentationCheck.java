/*
 * SonarQube Java
 * Copyright (C) 2012-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.java.checks;

import com.google.common.collect.Iterables;
import org.sonar.check.Rule;
import org.sonar.check.RuleProperty;
import org.sonar.java.RspecKey;
import org.sonar.java.model.JavaTree;
import org.sonar.plugins.java.api.JavaFileScanner;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.tree.BaseTreeVisitor;
import org.sonar.plugins.java.api.tree.BlockTree;
import org.sonar.plugins.java.api.tree.CaseGroupTree;
import org.sonar.plugins.java.api.tree.CaseLabelTree;
import org.sonar.plugins.java.api.tree.ClassTree;
import org.sonar.plugins.java.api.tree.LambdaExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.SwitchStatementTree;
import org.sonar.plugins.java.api.tree.SyntaxToken;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.Tree.Kind;

import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

@Rule(key = "IndentationCheck")
@RspecKey("S1120")
public class IndentationCheck extends BaseTreeVisitor implements JavaFileScanner {

  private static final int DEFAULT_INDENTATION_LEVEL = 2;

  @RuleProperty(
    key = "indentationLevel",
    description = "Number of white-spaces of an indent.",
    defaultValue = "" + DEFAULT_INDENTATION_LEVEL)
  public int indentationLevel = DEFAULT_INDENTATION_LEVEL;

  private int expectedLevel;
  private boolean isBlockAlreadyReported;
  private int lastCheckedLine;
  private Deque<Boolean> isInAnonymousClass = new LinkedList<>();
  private JavaFileScannerContext context;

  @Override
  public void scanFile(JavaFileScannerContext context) {
    expectedLevel = 0;
    isBlockAlreadyReported = false;
    lastCheckedLine = 0;
    this.context = context;
    scan(context.getTree());
    isInAnonymousClass.clear();
  }

  @Override
  public void visitClass(ClassTree tree) {
    // Exclude anonymous classes
    boolean isAnonymous = tree.simpleName() == null;
    isInAnonymousClass.push(isAnonymous);
    if (!isAnonymous) {
      checkIndentation(Collections.singletonList(tree));
    }
    newBlock();
    // Exclude anonymous classes
    if (!isAnonymous) {
      checkIndentation(tree.members());
    }
    super.visitClass(tree);
    leaveNode(tree);
    isInAnonymousClass.pop();
  }

  @Override
  public void visitBlock(BlockTree tree) {
    newBlock();
    adjustBlockForExceptionalParents(tree.parent());
    checkIndentation(tree.body());
    super.visitBlock(tree);
    restoreBlockForExceptionalParents(tree.parent());
    leaveNode(tree);
  }

  @Override
  public void visitSwitchStatement(SwitchStatementTree tree) {
    newBlock();
    scan(tree.expression());
    for (CaseGroupTree caseGroupTree : tree.cases()) {
      newBlock();
      checkCaseGroup(caseGroupTree);
      scan(caseGroupTree);
      leaveNode(caseGroupTree);
    }
    leaveNode(tree);
  }

  @Override
  public void visitMethodInvocation(MethodInvocationTree tree) {
    SyntaxToken firstToken = tree.firstToken();
    int parenthesisLine = tree.arguments().openParenToken().line();
    boolean shouldIndentArgs = firstToken.line() != parenthesisLine;
    scan(tree.methodSelect());
    scan(tree.typeArguments());
    if (shouldIndentArgs) {
      expectedLevel += indentationLevel;
    }
    scan(tree.arguments());
    if (shouldIndentArgs) {
      expectedLevel -= indentationLevel;
    }
  }

  @Override
  public void visitLambdaExpression(LambdaExpressionTree lambdaExpressionTree) {
    int previousTokenLine = getPreviousToken(lambdaExpressionTree).line();
    int lambdaFirstTokenLine = lambdaExpressionTree.firstToken().line();
    if (previousTokenLine != lambdaFirstTokenLine) {
      expectedLevel += indentationLevel;
    }
    super.visitLambdaExpression(lambdaExpressionTree);
    if (previousTokenLine != lambdaFirstTokenLine) {
      expectedLevel -= indentationLevel;
    }
  }

  private void newBlock() {
    expectedLevel += indentationLevel;
    isBlockAlreadyReported = false;
  }

  private void leaveNode(Tree tree) {
    expectedLevel -= indentationLevel;
    isBlockAlreadyReported = false;
    lastCheckedLine = tree.lastToken().line();
  }

  private static SyntaxToken getPreviousToken(Tree tree) {
    Tree previous = null;
    for (Tree children : ((JavaTree) tree.parent()).getChildren()) {
      if (children.equals(tree)) {
        break;
      }
      previous = children;
    }
    if(previous == null) {
      return getPreviousToken(tree.parent());
    }
    return previous.lastToken();
  }

  private void checkCaseGroup(CaseGroupTree tree) {
    List<CaseLabelTree> labels = tree.labels();
    if (labels.size() >= 2) {
      CaseLabelTree previousCaseLabelTree = labels.get(labels.size() - 2);
      lastCheckedLine = previousCaseLabelTree.lastToken().line();
    }
    List<StatementTree> body = tree.body();
    List<StatementTree> newBody = body;
    int bodySize = body.size();
    if (bodySize > 0 && body.get(0).is(Kind.BLOCK)) {
      expectedLevel -= indentationLevel;
      checkIndentation(body.get(0), Iterables.getLast(labels).colonToken().column() + 2);
      newBody = body.subList(1, bodySize);
    }
    checkIndentation(newBody);
    if (bodySize > 0 && body.get(0).is(Kind.BLOCK)) {
      expectedLevel += indentationLevel;
    }
  }

  private void adjustBlockForExceptionalParents(Tree parent) {
    if (parent.is(Kind.CASE_GROUP)) {
      expectedLevel -= indentationLevel;
    }
  }

  private void restoreBlockForExceptionalParents(Tree parent) {
    if (parent.is(Kind.CASE_GROUP)) {
      expectedLevel += indentationLevel;
    }
  }

  private void checkIndentation(List<? extends Tree> trees) {
    for (Tree tree : trees) {
      checkIndentation(tree, expectedLevel);
    }
  }

  private void checkIndentation(Tree tree, int expectedLevel) {
    SyntaxToken firstSyntaxToken = tree.firstToken();
    if (firstSyntaxToken.column() != expectedLevel && !isExcluded(tree, firstSyntaxToken.line())) {
      context.addIssue(((JavaTree) tree).getLine(), this, "Make this line start at column " + (expectedLevel + 1) + ".");
      isBlockAlreadyReported = true;
    }
    lastCheckedLine = tree.lastToken().line();
  }

  private boolean isExcluded(Tree node, int nodeLine) {
    return node.is(Kind.ENUM_CONSTANT) || isBlockAlreadyReported || lastCheckedLine == nodeLine || isInAnonymousClass.peek();
  }

}
