/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.physical.impl.unnest;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import org.apache.drill.exec.exception.SchemaChangeException;
import org.apache.drill.exec.ops.FragmentContext;
import org.apache.drill.exec.physical.base.LateralContract;
import org.apache.drill.exec.record.BatchSchema.SelectionVectorMode;
import org.apache.drill.exec.record.RecordBatch;
import org.apache.drill.exec.record.TransferPair;
import org.apache.drill.exec.vector.SchemaChangeCallBack;
import org.apache.drill.exec.vector.ValueVector;
import org.apache.drill.exec.vector.complex.RepeatedValueVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.apache.drill.exec.record.BatchSchema.SelectionVectorMode.NONE;

/**
 * Contains the actual unnest operation. Unnest is a simple transfer operation in this impelementation.
 * For use as a table function, we will need to change the logic of the unnest method to operate on
 * more than one row at a time and remove any dependence on Lateral
 * This class follows the pattern of other operators that generate code at runtime. Normally this class
 * would be abstract and have placeholders for doSetup and doEval. Unnest however, doesn't require code
 * generation so we can simply implement the code in a simple class that looks similar to the code gen
 * templates used by other operators but does not implement the doSetup and doEval methods.
 */
public class UnnestImpl implements Unnest {
  private static final Logger logger = LoggerFactory.getLogger(UnnestImpl.class);

  private ImmutableList<TransferPair> transfers;
  private LateralContract lateral; // corresponding lateral Join (or other operator implementing the Lateral Contract)
  private SelectionVectorMode svMode;
  private RepeatedValueVector fieldToUnnest;
  private RepeatedValueVector.RepeatedAccessor accessor;
  private RecordBatch outgoing;

  /**
   * The output batch limit starts at OUTPUT_ROW_COUNT, but may be decreased
   * if records are found to be large.
   */
  private int outputLimit = ValueVector.MAX_ROW_COUNT;


  // The index in the unnest column that is being processed.We start at zero and continue until
  // InnerValueCount is reached or  if the batch limit is reached
  // this allows for groups to be written between batches if we run out of space, for cases where we have finished
  // a batch on the boundary it will be set to 0
  private int innerValueIndex = 0;

  @Override
  public void setUnnestField(RepeatedValueVector unnestField) {
    this.fieldToUnnest = unnestField;
    this.accessor = RepeatedValueVector.RepeatedAccessor.class.cast(unnestField.getAccessor());
  }

  @Override
  public RepeatedValueVector getUnnestField() {
    return fieldToUnnest;
  }

  @Override
  public void setOutputCount(int outputCount) {
    outputLimit = outputCount;
  }

  @Override
  public final int unnestRecords(final int recordCount) {
    Preconditions.checkArgument(svMode == NONE, "Unnest does not support selection vector inputs.");
    if (innerValueIndex == -1) {
      innerValueIndex = 0;
    }

    // Current record being processed in the incoming record batch. We could keep
    // track of it ourselves, but it is better to check with the Lateral Join and get the
    // current record being processed thru the Lateral Join Contract.
    final int currentRecord = lateral.getRecordIndex();
    final int innerValueCount = accessor.getInnerValueCountAt(currentRecord);
    final int count = Math.min(Math.min(innerValueCount, outputLimit), recordCount);

    logger.debug("Unnest: currentRecord: {}, innerValueCount: {}, record count: {}, output limit: {}", innerValueCount,
        recordCount, outputLimit);
    final SchemaChangeCallBack callBack = new SchemaChangeCallBack();
    for (TransferPair t : transfers) {
      t.splitAndTransfer(innerValueIndex, count);

      // Get the corresponding ValueVector in output container and transfer the data
      final ValueVector vectorWithData = t.getTo();
      final ValueVector outputVector = outgoing.getContainer().addOrGet(vectorWithData.getField(), callBack);
      Preconditions.checkState(!callBack.getSchemaChangedAndReset(), "Outgoing container doesn't have " +
        "expected ValueVector of type %s, present in TransferPair of unnest field", vectorWithData.getClass());
      vectorWithData.makeTransferPair(outputVector).transfer();
    }
    innerValueIndex += count;
    return count;

  }

  @Override
  public final void setup(FragmentContext context, RecordBatch incoming, RecordBatch outgoing,
      List<TransferPair> transfers, LateralContract lateral) throws SchemaChangeException {

    this.svMode = incoming.getSchema().getSelectionVectorMode();
    this.outgoing = outgoing;
    if (svMode == NONE) {
      this.transfers = ImmutableList.copyOf(transfers);
      this.lateral = lateral;
    } else {
      throw new UnsupportedOperationException("Unnest does not support selection vector inputs.");
    }
  }

  @Override
  public void resetGroupIndex() {
    this.innerValueIndex = 0;
  }

  @Override
  public void close() {
    if (transfers != null) {
      for (TransferPair tp : transfers) {
        tp.getTo().close();
      }
      transfers = null;
    }
  }
}
