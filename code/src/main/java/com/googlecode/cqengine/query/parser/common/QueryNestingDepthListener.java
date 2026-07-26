// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.query.parser.common;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.TerminalNode;

final class QueryNestingDepthListener implements ParseTreeListener {

    private final int maxDepth;
    private final int queryRuleIndex;
    private final int simpleQueryRuleIndex;
    private int queryDepth;
    private int simpleQueryDepth;

    QueryNestingDepthListener(int maxDepth, int queryRuleIndex, int simpleQueryRuleIndex) {
        this.maxDepth = maxDepth;
        this.queryRuleIndex = queryRuleIndex;
        this.simpleQueryRuleIndex = simpleQueryRuleIndex;
    }

    @Override
    public void enterEveryRule(ParserRuleContext context) {
        int ruleIndex = context.getRuleIndex();
        if (ruleIndex == queryRuleIndex) {
            queryDepth++;
        }
        if (ruleIndex == simpleQueryRuleIndex) {
            simpleQueryDepth++;
        }
        int depth = queryDepth + Math.max(0, simpleQueryDepth - 1);
        if (depth > maxDepth) {
            throw new InvalidQueryException("Query exceeds maximum nesting depth of " + maxDepth);
        }
    }

    @Override
    public void exitEveryRule(ParserRuleContext context) {
        int ruleIndex = context.getRuleIndex();
        if (ruleIndex == simpleQueryRuleIndex) {
            simpleQueryDepth--;
        }
        if (ruleIndex == queryRuleIndex) {
            queryDepth--;
        }
    }

    @Override
    public void visitTerminal(TerminalNode node) {
    }

    @Override
    public void visitErrorNode(ErrorNode node) {
    }
}
