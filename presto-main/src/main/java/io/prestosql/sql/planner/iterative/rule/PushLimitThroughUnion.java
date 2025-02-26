/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableList;
import io.prestosql.matching.Capture;
import io.prestosql.matching.Captures;
import io.prestosql.matching.Pattern;
import io.prestosql.sql.planner.iterative.Rule;
import io.prestosql.sql.planner.plan.LimitNode;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.sql.planner.plan.UnionNode;

import static io.prestosql.matching.Capture.newCapture;
import static io.prestosql.sql.planner.optimizations.QueryCardinalityUtil.isAtMost;
import static io.prestosql.sql.planner.plan.Patterns.limit;
import static io.prestosql.sql.planner.plan.Patterns.source;
import static io.prestosql.sql.planner.plan.Patterns.union;

/**
 * Transforms:
 * <pre>
 * - Limit
 *    - Union
 *       - relation1
 *       - relation2
 *       ..
 * </pre>
 * Into:
 * <pre>
 * - Limit
 *    - Union
 *       - Limit
 *          - relation1
 *       - Limit
 *          - relation2
 *       ..
 * </pre>
 */
public class PushLimitThroughUnion
        implements Rule<LimitNode>
{
    private static final Capture<UnionNode> CHILD = newCapture();

    private static final Pattern<LimitNode> PATTERN =
            limit().with(source().matching(union().capturedAs(CHILD)));

    @Override
    public Pattern<LimitNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Result apply(LimitNode parent, Captures captures, Context context)
    {
        UnionNode unionNode = captures.get(CHILD);
        ImmutableList.Builder<PlanNode> builder = ImmutableList.builder();
        boolean shouldApply = false;
        for (PlanNode source : unionNode.getSources()) {
            // This check is to ensure that we don't fire the optimizer if it was previously applied.
            if (isAtMost(source, context.getLookup(), parent.getCount())) {
                builder.add(source);
            }
            else {
                shouldApply = true;
                builder.add(new LimitNode(context.getIdAllocator().getNextId(), source, parent.getCount(), true));
            }
        }

        if (!shouldApply) {
            return Result.empty();
        }

        return Result.ofPlanNode(
                parent.replaceChildren(ImmutableList.of(
                        unionNode.replaceChildren(builder.build()))));
    }
}
