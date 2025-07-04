// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.planner;

import org.apache.doris.analysis.Analyzer;
import org.apache.doris.analysis.Expr;
import org.apache.doris.analysis.ExprSubstitutionMap;
import org.apache.doris.analysis.JoinOperator;
import org.apache.doris.analysis.SlotDescriptor;
import org.apache.doris.analysis.SlotId;
import org.apache.doris.analysis.SlotRef;
import org.apache.doris.analysis.TableRef;
import org.apache.doris.analysis.TupleDescriptor;
import org.apache.doris.analysis.TupleId;
import org.apache.doris.analysis.TupleIsNullPredicate;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.Pair;
import org.apache.doris.common.UserException;
import org.apache.doris.statistics.StatisticalType;
import org.apache.doris.thrift.TNullSide;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class JoinNodeBase extends PlanNode {
    private static final Logger LOG = LogManager.getLogger(JoinNodeBase.class);

    protected final TableRef innerRef;
    protected final JoinOperator joinOp;
    protected final boolean isMark;
    protected ExprSubstitutionMap vSrcToOutputSMap;
    protected List<TupleDescriptor> vIntermediateTupleDescList;

    public JoinNodeBase(PlanNodeId id, String planNodeName, StatisticalType statisticalType,
            PlanNode outer, PlanNode inner, TableRef innerRef) {
        super(id, planNodeName, statisticalType);
        this.innerRef = innerRef;
        tblRefIds.addAll(outer.getTblRefIds());
        tblRefIds.addAll(inner.getTblRefIds());
        children.add(outer);
        children.add(inner);

        // Inherits all the nullable tuple from the children
        // Mark tuples that form the "nullable" side of the outer join as nullable.
        nullableTupleIds.addAll(outer.getNullableTupleIds());
        nullableTupleIds.addAll(inner.getNullableTupleIds());

        joinOp = innerRef.getJoinOp();
        if (joinOp.equals(JoinOperator.FULL_OUTER_JOIN)) {
            nullableTupleIds.addAll(outer.getOutputTupleIds());
            nullableTupleIds.addAll(inner.getOutputTupleIds());
        } else if (joinOp.equals(JoinOperator.LEFT_OUTER_JOIN)) {
            nullableTupleIds.addAll(inner.getOutputTupleIds());
        } else if (joinOp.equals(JoinOperator.RIGHT_OUTER_JOIN)) {
            nullableTupleIds.addAll(outer.getOutputTupleIds());
        }
        this.isMark = this.innerRef != null && innerRef.isMark();
    }

    public boolean isMarkJoin() {
        return isMark;
    }

    public JoinOperator getJoinOp() {
        return joinOp;
    }

    protected boolean isMaterializedByChild(SlotDescriptor slotDesc, ExprSubstitutionMap smap) {
        if (slotDesc.isMaterialized()) {
            return true;
        }
        Expr child = smap.get(new SlotRef(slotDesc));
        if (child == null) {
            return false;
        }
        List<SlotRef> slotRefList = Lists.newArrayList();
        child.collect(SlotRef.class, slotRefList);
        for (SlotRef slotRef : slotRefList) {
            if (slotRef.getDesc() != null && !slotRef.getDesc().isMaterialized()) {
                return false;
            }
        }
        return true;
    }

    protected void computeOutputTuple(Analyzer analyzer) throws UserException {
        // 1. create new tuple
        outputTupleDesc = analyzer.getDescTbl().createTupleDescriptor();
        boolean copyLeft = false;
        boolean copyRight = false;
        boolean leftNullable = false;
        boolean rightNullable = false;
        switch (joinOp) {
            case INNER_JOIN:
            case CROSS_JOIN:
                copyLeft = true;
                copyRight = true;
                break;
            case LEFT_OUTER_JOIN:
                copyLeft = true;
                copyRight = true;
                rightNullable = true;
                break;
            case RIGHT_OUTER_JOIN:
                copyLeft = true;
                copyRight = true;
                leftNullable = true;
                break;
            case FULL_OUTER_JOIN:
                copyLeft = true;
                copyRight = true;
                leftNullable = true;
                rightNullable = true;
                break;
            case LEFT_ANTI_JOIN:
            case LEFT_SEMI_JOIN:
            case NULL_AWARE_LEFT_ANTI_JOIN:
                copyLeft = true;
                break;
            case RIGHT_ANTI_JOIN:
            case RIGHT_SEMI_JOIN:
                copyRight = true;
                break;
            default:
                break;
        }
        ExprSubstitutionMap srcTblRefToOutputTupleSmap = new ExprSubstitutionMap();
        int leftNullableNumber = 0;
        int rightNullableNumber = 0;
        if (copyLeft) {
            //cross join do not have OutputTblRefIds
            List<TupleId> srcTupleIds = getChild(0) instanceof JoinNodeBase ? getChild(0).getOutputTupleIds()
                    : getChild(0).getOutputTblRefIds();
            for (TupleDescriptor leftTupleDesc : analyzer.getDescTbl().getTupleDesc(srcTupleIds)) {
                // if the child is cross join node, the only way to get the correct nullable info of its output slots
                // is to check if the output tuple ids are outer joined or not.
                // then pass this nullable info to hash join node will be correct.
                boolean needSetToNullable =
                        getChild(0) instanceof JoinNodeBase && analyzer.isOuterJoined(leftTupleDesc.getId());
                for (SlotDescriptor leftSlotDesc : leftTupleDesc.getSlots()) {
                    if (!isMaterializedByChild(leftSlotDesc, getChild(0).getOutputSmap())) {
                        continue;
                    }
                    SlotDescriptor outputSlotDesc =
                            analyzer.getDescTbl().copySlotDescriptor(outputTupleDesc, leftSlotDesc);
                    if (leftNullable) {
                        outputSlotDesc.setIsNullable(true);
                        leftNullableNumber++;
                    }
                    if (needSetToNullable) {
                        outputSlotDesc.setIsNullable(true);
                    }
                    srcTblRefToOutputTupleSmap.put(new SlotRef(leftSlotDesc), new SlotRef(outputSlotDesc));
                }
            }
        }
        if (copyRight) {
            List<TupleId> srcTupleIds = getChild(1) instanceof JoinNodeBase ? getChild(1).getOutputTupleIds()
                    : getChild(1).getOutputTblRefIds();
            for (TupleDescriptor rightTupleDesc : analyzer.getDescTbl().getTupleDesc(srcTupleIds)) {
                boolean needSetToNullable =
                        getChild(1) instanceof JoinNodeBase && analyzer.isOuterJoined(rightTupleDesc.getId());
                for (SlotDescriptor rightSlotDesc : rightTupleDesc.getSlots()) {
                    if (!isMaterializedByChild(rightSlotDesc, getChild(1).getOutputSmap())) {
                        continue;
                    }
                    SlotDescriptor outputSlotDesc =
                            analyzer.getDescTbl().copySlotDescriptor(outputTupleDesc, rightSlotDesc);
                    if (rightNullable) {
                        outputSlotDesc.setIsNullable(true);
                        rightNullableNumber++;
                    }
                    if (needSetToNullable) {
                        outputSlotDesc.setIsNullable(true);
                    }
                    srcTblRefToOutputTupleSmap.put(new SlotRef(rightSlotDesc), new SlotRef(outputSlotDesc));
                }
            }
        }

        // add mark slot if needed
        if (isMarkJoin() && analyzer.needPopUpMarkTuple(innerRef)) {
            SlotDescriptor markSlot = analyzer.getMarkTuple(innerRef).getSlots().get(0);
            SlotDescriptor outputSlotDesc =
                    analyzer.getDescTbl().copySlotDescriptor(outputTupleDesc, markSlot);
            srcTblRefToOutputTupleSmap.put(new SlotRef(markSlot), new SlotRef(outputSlotDesc));
        }

        // 2. compute srcToOutputMap
        vSrcToOutputSMap = ExprSubstitutionMap.subtraction(outputSmap, srcTblRefToOutputTupleSmap, analyzer);
        for (int i = 0; i < vSrcToOutputSMap.size(); i++) {
            Preconditions.checkState(vSrcToOutputSMap.getRhs().get(i) instanceof SlotRef);
            SlotRef rSlotRef = (SlotRef) vSrcToOutputSMap.getRhs().get(i);
            if (vSrcToOutputSMap.getLhs().get(i) instanceof SlotRef) {
                SlotRef lSlotRef = (SlotRef) vSrcToOutputSMap.getLhs().get(i);
                rSlotRef.getDesc().setIsMaterialized(lSlotRef.getDesc().isMaterialized());
            } else {
                rSlotRef.getDesc().setIsMaterialized(true);
                rSlotRef.materializeSrcExpr();
            }
        }

        outputTupleDesc.computeStatAndMemLayout();
        // 3. add tupleisnull in null-side
        Preconditions.checkState(srcTblRefToOutputTupleSmap.getLhs().size() == vSrcToOutputSMap.getLhs().size());
        // Condition1: the left child is null-side
        // Condition2: the left child is a inline view
        // Then: add tuple is null in left child columns
        if (leftNullable && getChild(0).getTblRefIds().size() == 1
                && analyzer.isInlineView(getChild(0).getTblRefIds().get(0))) {
            List<Expr> tupleIsNullLhs = TupleIsNullPredicate.wrapExprs(
                    vSrcToOutputSMap.getLhs().subList(0, leftNullableNumber), new ArrayList<>(), TNullSide.LEFT,
                    analyzer);
            tupleIsNullLhs
                    .addAll(vSrcToOutputSMap.getLhs().subList(leftNullableNumber, vSrcToOutputSMap.getLhs().size()));
            vSrcToOutputSMap.updateLhsExprs(tupleIsNullLhs);
        }
        // Condition1: the right child is null-side
        // Condition2: the right child is a inline view
        // Then: add tuple is null in right child columns
        if (rightNullable && getChild(1).getTblRefIds().size() == 1
                && analyzer.isInlineView(getChild(1).getTblRefIds().get(0))) {
            if (rightNullableNumber != 0) {
                int rightBeginIndex = vSrcToOutputSMap.size() - rightNullableNumber;
                List<Expr> tupleIsNullLhs = TupleIsNullPredicate.wrapExprs(
                        vSrcToOutputSMap.getLhs().subList(rightBeginIndex, vSrcToOutputSMap.size()), new ArrayList<>(),
                        TNullSide.RIGHT, analyzer);
                List<Expr> newLhsList = Lists.newArrayList();
                if (rightBeginIndex > 0) {
                    newLhsList.addAll(vSrcToOutputSMap.getLhs().subList(0, rightBeginIndex));
                }
                newLhsList.addAll(tupleIsNullLhs);
                vSrcToOutputSMap.updateLhsExprs(newLhsList);
            }
        }
        // 4. change the outputSmap
        outputSmap = ExprSubstitutionMap.composeAndReplace(outputSmap, srcTblRefToOutputTupleSmap, analyzer);
    }

    protected abstract Pair<Boolean, Boolean> needToCopyRightAndLeft();

    protected abstract void computeOtherConjuncts(Analyzer analyzer, ExprSubstitutionMap originToIntermediateSmap);

    protected void computeIntermediateTuple(Analyzer analyzer) throws AnalysisException {
        // 1. create new tuple
        TupleDescriptor vIntermediateLeftTupleDesc = analyzer.getDescTbl().createTupleDescriptor();
        TupleDescriptor vIntermediateRightTupleDesc = analyzer.getDescTbl().createTupleDescriptor();
        vIntermediateTupleDescList = new ArrayList<>();
        vIntermediateTupleDescList.add(vIntermediateLeftTupleDesc);
        vIntermediateTupleDescList.add(vIntermediateRightTupleDesc);
        // if join type is MARK, add mark tuple to intermediate tuple. mark slot will be generated after join.
        if (isMarkJoin()) {
            TupleDescriptor markTuple = analyzer.getMarkTuple(innerRef);
            if (markTuple != null) {
                vIntermediateTupleDescList.add(markTuple);
            }
        }
        boolean leftNullable = false;
        boolean rightNullable = false;

        switch (joinOp) {
            case LEFT_OUTER_JOIN:
                rightNullable = true;
                break;
            case RIGHT_OUTER_JOIN:
                leftNullable = true;
                break;
            case FULL_OUTER_JOIN:
                leftNullable = true;
                rightNullable = true;
                break;
            default:
                break;
        }
        Pair<Boolean, Boolean> tmpPair = needToCopyRightAndLeft();
        boolean copyleft = tmpPair.first;
        boolean copyRight = tmpPair.second;
        // 2. exprsmap: <originslot, intermediateslot>
        ExprSubstitutionMap originToIntermediateSmap = new ExprSubstitutionMap();
        Map<List<TupleId>, TupleId> originTidsToIntermediateTidMap = Maps.newHashMap();
        // left
        if (copyleft) {
            originTidsToIntermediateTidMap.put(getChild(0).getOutputTupleIds(), vIntermediateLeftTupleDesc.getId());
            for (TupleDescriptor tupleDescriptor : analyzer.getDescTbl()
                    .getTupleDesc(getChild(0).getOutputTupleIds())) {
                for (SlotDescriptor slotDescriptor : tupleDescriptor.getMaterializedSlots()) {
                    SlotDescriptor intermediateSlotDesc =
                            analyzer.getDescTbl().copySlotDescriptor(vIntermediateLeftTupleDesc, slotDescriptor);
                    if (leftNullable) {
                        intermediateSlotDesc.setIsNullable(true);
                    }
                    originToIntermediateSmap.put(new SlotRef(slotDescriptor), new SlotRef(intermediateSlotDesc));
                }
            }
        }
        vIntermediateLeftTupleDesc.computeMemLayout();
        // right
        if (copyRight) {
            originTidsToIntermediateTidMap.put(getChild(1).getOutputTupleIds(), vIntermediateRightTupleDesc.getId());
            for (TupleDescriptor tupleDescriptor : analyzer.getDescTbl()
                    .getTupleDesc(getChild(1).getOutputTupleIds())) {
                for (SlotDescriptor slotDescriptor : tupleDescriptor.getMaterializedSlots()) {
                    SlotDescriptor intermediateSlotDesc =
                            analyzer.getDescTbl().copySlotDescriptor(vIntermediateRightTupleDesc, slotDescriptor);
                    if (rightNullable) {
                        intermediateSlotDesc.setIsNullable(true);
                    }
                    originToIntermediateSmap.put(new SlotRef(slotDescriptor), new SlotRef(intermediateSlotDesc));
                }
            }
        }
        vIntermediateRightTupleDesc.computeMemLayout();
        // 3. replace srcExpr by intermediate tuple
        Preconditions.checkState(vSrcToOutputSMap != null);
        // Set `preserveRootTypes` to true because we should keep the consistent for types. See Issue-11314.
        vSrcToOutputSMap.substituteLhs(originToIntermediateSmap, analyzer, true);
        // 4. replace other conjuncts and conjuncts
        computeOtherConjuncts(analyzer, originToIntermediateSmap);
        conjuncts = Expr.substituteList(conjuncts, originToIntermediateSmap, analyzer, false);
        // 5. replace tuple is null expr
        TupleIsNullPredicate.substitueListForTupleIsNull(vSrcToOutputSMap.getLhs(), originTidsToIntermediateTidMap);

        Preconditions.checkState(vSrcToOutputSMap.getLhs().size() == outputTupleDesc.getSlots().size());
        List<Expr> exprs = vSrcToOutputSMap.getLhs();
        ArrayList<SlotDescriptor> slots = outputTupleDesc.getSlots();
        for (int i = 0; i < slots.size(); i++) {
            slots.get(i).setIsNullable(exprs.get(i).isNullable());
        }
        vSrcToOutputSMap.reCalculateNullableInfoForSlotInRhs();
        outputTupleDesc.computeMemLayout();
    }

    protected abstract List<SlotId> computeSlotIdsForJoinConjuncts(Analyzer analyzer);

    /**
     * Only for Nereids.
     */
    public JoinNodeBase(PlanNodeId id, String planNodeName,
                        StatisticalType statisticalType, JoinOperator joinOp, boolean isMark) {
        super(id, planNodeName, statisticalType);
        this.innerRef = null;
        this.joinOp = joinOp;
        this.isMark = isMark;
    }

    public TableRef getInnerRef() {
        return innerRef;
    }


    /**
     * If parent wants to get join node tupleids,
     * it will call this function instead of read properties directly.
     * The reason is that the tuple id of outputTupleDesc the real output tuple id for join node.
     * <p>
     * If you read the properties of @tupleids directly instead of this function,
     * it reads the input id of the current node.
     */
    @Override
    public ArrayList<TupleId> getTupleIds() {
        Preconditions.checkState(tupleIds != null);
        if (outputTupleDesc != null) {
            return Lists.newArrayList(outputTupleDesc.getId());
        }
        return tupleIds;
    }

    @Override
    public ArrayList<TupleId> getOutputTblRefIds() {
        if (outputTupleDesc != null) {
            return Lists.newArrayList(outputTupleDesc.getId());
        }
        switch (joinOp) {
            case LEFT_SEMI_JOIN:
            case LEFT_ANTI_JOIN:
            case NULL_AWARE_LEFT_ANTI_JOIN:
                return getChild(0).getOutputTblRefIds();
            case RIGHT_SEMI_JOIN:
            case RIGHT_ANTI_JOIN:
                return getChild(1).getOutputTblRefIds();
            default:
                return getTblRefIds();
        }
    }

    @Override
    public List<TupleId> getOutputTupleIds() {
        if (outputTupleDesc != null) {
            return Lists.newArrayList(outputTupleDesc.getId());
        }
        switch (joinOp) {
            case LEFT_SEMI_JOIN:
            case LEFT_ANTI_JOIN:
            case NULL_AWARE_LEFT_ANTI_JOIN:
                return getChild(0).getOutputTupleIds();
            case RIGHT_SEMI_JOIN:
            case RIGHT_ANTI_JOIN:
                return getChild(1).getOutputTupleIds();
            default:
                return tupleIds;
        }
    }

    @Override
    public int getNumInstances() {
        return Math.max(children.get(0).getNumInstances(), children.get(1).getNumInstances());
    }

    /**
     * Used by nereids.
     */
    public void setvIntermediateTupleDescList(List<TupleDescriptor> vIntermediateTupleDescList) {
        this.vIntermediateTupleDescList = vIntermediateTupleDescList;
    }

    public void setOutputSmap(ExprSubstitutionMap smap, Analyzer analyzer) {
        outputSmap = smap;
        ExprSubstitutionMap tmpSmap = new ExprSubstitutionMap(Lists.newArrayList(vSrcToOutputSMap.getRhs()),
                Lists.newArrayList(vSrcToOutputSMap.getLhs()));
        List<Expr> newRhs = Lists.newArrayList();
        boolean bSmapChanged = false;
        for (Expr rhsExpr : smap.getRhs()) {
            if (rhsExpr instanceof SlotRef || !rhsExpr.isBound(outputTupleDesc.getId())) {
                newRhs.add(rhsExpr);
            } else {
                // we need do project in the join node
                // add a new slot for projection result and add the project expr to vSrcToOutputSMap
                SlotDescriptor slotDesc = analyzer.addSlotDescriptor(outputTupleDesc);
                slotDesc.initFromExpr(rhsExpr);
                slotDesc.setIsMaterialized(true);
                // the project expr is from smap, which use the slots of hash join node's output tuple
                // we need substitute it to make sure the project expr use slots from intermediate tuple
                rhsExpr = rhsExpr.substitute(tmpSmap);
                vSrcToOutputSMap.getLhs().add(rhsExpr);
                SlotRef slotRef = new SlotRef(slotDesc);
                slotRef.materializeSrcExpr();
                vSrcToOutputSMap.getRhs().add(slotRef);
                newRhs.add(slotRef);
                bSmapChanged = true;
            }
        }

        if (bSmapChanged) {
            outputSmap.updateRhsExprs(newRhs);
            outputTupleDesc.computeStatAndMemLayout();
        }
    }
}
