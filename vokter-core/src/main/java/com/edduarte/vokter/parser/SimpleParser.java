/*
 * Copyright 2015 Eduardo Duarte
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.edduarte.vokter.parser;

import com.edduarte.vokter.stemmer.Stemmer;
import com.edduarte.vokter.stopper.Stopper;
import it.unimi.dsi.lang.MutableString;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple parser implementation, which tokenizes, stops and stems words
 * separated by the specified character in the constructor. By default, the
 * tokenization is whitespace-based.
 *
 * @author Eduardo Duarte (<a href="mailto:hello@edduarte.com">hello@edduarte.com</a>)
 * @version 1.3.2
 * @since 1.0.0
 */
public class SimpleParser implements Parser {

    private final char separator;


    public SimpleParser() {
        this(' ');
    }


    public SimpleParser(char separator) {
        this.separator = separator;
    }


    @Override
    public List<Result> parse(final MutableString text,
                              final Stopper stopper,
                              final Stemmer stemmer,
                              final boolean ignoreCase) {
        List<Result> r = new ArrayList<>();

        boolean loop = true;
        int start = 0, count = 0, end;
        do {
            end = text.indexOf(separator, start);

            if (end < 0) {
                // is the last word, add it as a token and stop the loop
                end = text.length();
                loop = false;
            }

            if (start == end) {
                // is empty or the first character in the text is a space, so
                // skip it
                start++;
                continue;
            }

            final MutableString token = text.substring(start, end);

            // clean trailing spaces
//            token.trim();

            // if after trimming the string is empty, then there
            // is no valuable token to collect
            if (token.length() == 0) {
                start = end + 1;
                continue;
            }

            if (ignoreCase) {
                token.toLowerCase();
            }

            // checks if the text is a stopword
            // if true, do not stem it nor add it to the ParserResult list
            boolean isStopword = false;
            if (stopper != null) {
                MutableString test = token;
                if (!ignoreCase) {
                    test = test.copy().toLowerCase();
                }
                isStopword = stopper.isStopword(test);
            }
            if (!isStopword) {

                // stems the term text
                if (stemmer != null) {
                    stemmer.stem(token);
                }

                r.add(new Result(count++, start, end, token));
            }

            start = end + 1;
        } while (loop);

        return r;
    }


    @Override
    public void close() {
    }
}
