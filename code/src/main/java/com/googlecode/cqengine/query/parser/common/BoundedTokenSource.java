// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.query.parser.common;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenFactory;
import org.antlr.v4.runtime.TokenSource;

final class BoundedTokenSource implements TokenSource {

    private final TokenSource delegate;
    private final int maxTokens;
    private int tokenCount;

    BoundedTokenSource(TokenSource delegate, int maxTokens) {
        this.delegate = delegate;
        this.maxTokens = maxTokens;
    }

    @Override
    public Token nextToken() {
        Token token = delegate.nextToken();
        if (token.getType() != Token.EOF && ++tokenCount > maxTokens) {
            throw new InvalidQueryException("Query exceeds maximum token count of " + maxTokens);
        }
        return token;
    }

    @Override
    public int getLine() {
        return delegate.getLine();
    }

    @Override
    public int getCharPositionInLine() {
        return delegate.getCharPositionInLine();
    }

    @Override
    public CharStream getInputStream() {
        return delegate.getInputStream();
    }

    @Override
    public String getSourceName() {
        return delegate.getSourceName();
    }

    @Override
    public void setTokenFactory(TokenFactory<?> factory) {
        delegate.setTokenFactory(factory);
    }

    @Override
    public TokenFactory<?> getTokenFactory() {
        return delegate.getTokenFactory();
    }
}
