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
package org.apache.drill.exec.physical.impl.agg;

import org.apache.drill.categories.OperatorTest;
import org.apache.drill.common.types.TypeProtos;
import org.apache.drill.exec.physical.config.StreamingAggregate;
import org.apache.drill.exec.physical.impl.BaseTestOpBatchEmitOutcome;
import org.apache.drill.exec.physical.impl.MockRecordBatch;
import org.apache.drill.exec.physical.impl.aggregate.StreamingAggBatch;
import org.apache.drill.exec.record.RecordBatch;
import org.apache.drill.exec.record.metadata.TupleMetadata;
import org.apache.drill.test.rowSet.DirectRowSet;
import org.apache.drill.test.rowSet.RowSet;
import org.apache.drill.test.rowSet.RowSetComparison;
import org.apache.drill.test.rowSet.schema.SchemaBuilder;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.apache.drill.exec.record.RecordBatch.IterOutcome.EMIT;
import static org.apache.drill.exec.record.RecordBatch.IterOutcome.OK_NEW_SCHEMA;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@Category(OperatorTest.class)
public class TestStreamingAggEmitOutcome extends BaseTestOpBatchEmitOutcome {
  //private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(TestStreamingAggEmitOutcome.class);
  protected static TupleMetadata resultSchema;

  @BeforeClass
  public static void setUpBeforeClass2() throws Exception {
    resultSchema = new SchemaBuilder()
        .add("name", TypeProtos.MinorType.VARCHAR)
        .addNullable("total_sum", TypeProtos.MinorType.BIGINT)
        .buildSchema();
  }

  /**
   * Verifies that if StreamingAggBatch receives empty batches with OK_NEW_SCHEMA and EMIT outcome then it correctly produces
   * empty batches as output. First empty batch will be with OK_NEW_SCHEMA and second will be with EMIT outcome.
   */
  @Test
  public void t1_testStreamingAggrEmptyBatchEmitOutcome() {
    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(emptyInputRowSet.container());

    inputOutcomes.add(OK_NEW_SCHEMA);
    inputOutcomes.add(OK_NEW_SCHEMA);
    inputOutcomes.add(EMIT);

    final MockRecordBatch mockInputBatch = new MockRecordBatch(operatorFixture.getFragmentContext(), opContext,
      inputContainer, inputOutcomes, emptyInputRowSet.container().getSchema());

    final StreamingAggregate streamAggrConfig = new StreamingAggregate(null,
      parseExprs("name_left", "name"),
      parseExprs("sum(id_left+cost_left)", "total_sum"),
      1.0f);

    final StreamingAggBatch strAggBatch = new StreamingAggBatch(streamAggrConfig, mockInputBatch,
      operatorFixture.getFragmentContext());

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.EMIT);
    assertEquals(0, strAggBatch.getRecordCount());
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.NONE);
  }

  /**
   * Verifies that if StreamingAgg receives a RecordBatch with EMIT outcome post build schema phase then it produces
   * output for those input batch correctly. The first output batch will always be returned with OK_NEW_SCHEMA
   * outcome followed by EMIT with empty batch. The test verifies the output order with the expected baseline.
   */
  @Test
  public void t2_testStreamingAggrNonEmptyBatchEmitOutcome() {
    final RowSet.SingleRowSet nonEmptyInputRowSet2 = operatorFixture.rowSetBuilder(inputSchema)
      .addRow(13, 130, "item13")
      .addRow(13, 130, "item13")
      .addRow(2, 20, "item2")
      .addRow(2, 20, "item2")
      .addRow(4, 40, "item4")
      .build();

    final RowSet.SingleRowSet expectedRowSet = operatorFixture.rowSetBuilder(resultSchema)
      .addRow("item1", (long)11)
      .addRow("item13", (long)286)
      .addRow("item2", (long)44)
      .addRow("item4", (long)44)
      .build();

    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(nonEmptyInputRowSet.container());
    inputContainer.add(nonEmptyInputRowSet2.container());

    inputOutcomes.add(RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    inputOutcomes.add(RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    inputOutcomes.add(RecordBatch.IterOutcome.EMIT);

    final MockRecordBatch mockInputBatch = new MockRecordBatch(operatorFixture.getFragmentContext(), opContext,
      inputContainer, inputOutcomes, emptyInputRowSet.container().getSchema());

    final StreamingAggregate streamAggrConfig = new StreamingAggregate(null,
      parseExprs("name_left", "name"),
      parseExprs("sum(id_left+cost_left)", "total_sum"),
      1.0f);

    final StreamingAggBatch strAggBatch = new StreamingAggBatch(streamAggrConfig, mockInputBatch,
      operatorFixture.getFragmentContext());

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    // Data before EMIT is returned with an OK_NEW_SCHEMA.
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    assertEquals(4, strAggBatch.getRecordCount());

    RowSet actualRowSet = DirectRowSet.fromContainer(strAggBatch.getContainer());
    new RowSetComparison(expectedRowSet).verify(actualRowSet);

    // EMIT comes with an empty batch
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.EMIT);

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.NONE);

    // Release memory for row sets
    nonEmptyInputRowSet2.clear();
    expectedRowSet.clear();
  }

  @Test
  public void t3_testStreamingAggrEmptyBatchFollowedByNonEmptyBatchEmitOutcome() {
    final RowSet.SingleRowSet nonEmptyInputRowSet2 = operatorFixture.rowSetBuilder(inputSchema)
      .addRow(13, 130, "item13")
      .addRow(0, 1300, "item13")
      .addRow(2, 20, "item2")
      .addRow(0, 2000, "item2")
      .addRow(4, 40, "item4")
      .addRow(0, 4000, "item4")
      .build();

    final RowSet.SingleRowSet expectedRowSet = operatorFixture.rowSetBuilder(resultSchema)
      .addRow("item13", (long)1443)
      .addRow("item2", (long)2022)
      .addRow("item4", (long)4044)
      .build();

    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(nonEmptyInputRowSet2.container());

    inputOutcomes.add(RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    inputOutcomes.add(RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    inputOutcomes.add(RecordBatch.IterOutcome.EMIT);
    inputOutcomes.add(RecordBatch.IterOutcome.EMIT);

    final MockRecordBatch mockInputBatch = new MockRecordBatch(operatorFixture.getFragmentContext(), opContext,
      inputContainer, inputOutcomes, emptyInputRowSet.container().getSchema());

    final StreamingAggregate streamAggrConfig = new StreamingAggregate(null,
      parseExprs("name_left", "name"),
      parseExprs("sum(id_left+cost_left)", "total_sum"),
      1.0f);

    final StreamingAggBatch strAggBatch = new StreamingAggBatch(streamAggrConfig, mockInputBatch,
      operatorFixture.getFragmentContext());

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.EMIT);
    assertEquals(0, strAggBatch.getRecordCount());
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.EMIT);
    assertEquals(3, strAggBatch.getRecordCount());

    RowSet actualRowSet = DirectRowSet.fromContainer(strAggBatch.getContainer());
    new RowSetComparison(expectedRowSet).verify(actualRowSet);

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.NONE);

    // Release memory for row sets
    nonEmptyInputRowSet2.clear();
    expectedRowSet.clear();
  }

  @Test
  public void t4_testStreamingAggrMultipleEmptyBatchFollowedByNonEmptyBatchEmitOutcome() {
    final RowSet.SingleRowSet nonEmptyInputRowSet2 = operatorFixture.rowSetBuilder(inputSchema)
      .addRow(13, 130, "item13")
      .addRow(0, 0, "item13")
      .addRow(1, 33000, "item13")
      .addRow(2, 20, "item2")
      .addRow(0, 0, "item2")
      .addRow(1, 11000, "item2")
      .addRow(4, 40, "item4")
      .build();

    final RowSet.SingleRowSet expectedRowSet = operatorFixture.rowSetBuilder(resultSchema)
      .addRow("item13", (long)33144)
      .addRow("item2", (long)11023)
      .addRow("item4", (long)44)
      .build();

    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(nonEmptyInputRowSet2.container());

    inputOutcomes.add(RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    inputOutcomes.add(RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    inputOutcomes.add(RecordBatch.IterOutcome.EMIT);
    inputOutcomes.add(RecordBatch.IterOutcome.EMIT);
    inputOutcomes.add(RecordBatch.IterOutcome.EMIT);
    inputOutcomes.add(RecordBatch.IterOutcome.EMIT);

    final MockRecordBatch mockInputBatch = new MockRecordBatch(operatorFixture.getFragmentContext(), opContext,
      inputContainer, inputOutcomes, emptyInputRowSet.container().getSchema());

    final StreamingAggregate streamAggrConfig = new StreamingAggregate(null,
      parseExprs("name_left", "name"),
      parseExprs("sum(id_left+cost_left)", "total_sum"),
      1.0f);

    final StreamingAggBatch strAggBatch = new StreamingAggBatch(streamAggrConfig, mockInputBatch,
      operatorFixture.getFragmentContext());

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.EMIT);
    assertEquals(0, strAggBatch.getRecordCount());
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.EMIT);
    assertEquals(0, strAggBatch.getRecordCount());
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.EMIT);
    assertEquals(0, strAggBatch.getRecordCount());
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.EMIT);
    assertEquals(3, strAggBatch.getRecordCount());

    RowSet actualRowSet = DirectRowSet.fromContainer(strAggBatch.getContainer());
    new RowSetComparison(expectedRowSet).verify(actualRowSet);

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.NONE);

    // Release memory for row sets
    nonEmptyInputRowSet2.clear();
    expectedRowSet.clear();
  }

  /**
   * Verifies that if StreamingAggr receives multiple non-empty record batch with EMIT outcome in between then it produces
   * output for those input batch correctly. In this case it receives first non-empty batch with OK_NEW_SCHEMA in
   * buildSchema phase followed by an empty batch with EMIT outcome. For this combination it produces output for the
   * record received so far along with EMIT outcome. Then it receives second non-empty batch with OK outcome and
   * produces output for it differently. The test validates that for each output received the order of the records are
   * correct.
   * @throws Exception
   */
  @Test
  public void t5_testStreamingAgrResetsAfterFirstEmitOutcome() {
    final RowSet.SingleRowSet nonEmptyInputRowSet2 = operatorFixture.rowSetBuilder(inputSchema)
      .addRow(2, 20, "item2")
      .addRow(2, 20, "item2")
      .addRow(3, 30, "item3")
      .addRow(3, 30, "item3")
      .addRow(3, 30, "item3")
      .addRow(3, 30, "item3")
      .addRow(3, 30, "item3")
      .addRow(3, 30, "item3")
      .addRow(3, 30, "item3")
      .addRow(3, 30, "item3")
      .addRow(3, 30, "item3")
      .addRow(3, 30, "item3")
      .build();

    final RowSet.SingleRowSet expectedRowSet1 = operatorFixture.rowSetBuilder(resultSchema)
      .addRow("item1", (long)11)
      .build();

    final RowSet.SingleRowSet expectedRowSet2 = operatorFixture.rowSetBuilder(resultSchema)
      .addRow("item2", (long)44)
      .addRow("item3", (long)330)
      .build();

    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(nonEmptyInputRowSet.container());
    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(nonEmptyInputRowSet2.container());
    inputContainer.add(emptyInputRowSet.container());

    inputOutcomes.add(RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    inputOutcomes.add(RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    inputOutcomes.add(RecordBatch.IterOutcome.EMIT);
    inputOutcomes.add(RecordBatch.IterOutcome.OK);
    inputOutcomes.add(RecordBatch.IterOutcome.EMIT);

    final MockRecordBatch mockInputBatch = new MockRecordBatch(operatorFixture.getFragmentContext(), opContext,
      inputContainer, inputOutcomes, emptyInputRowSet.container().getSchema());

    final StreamingAggregate streamAggrConfig = new StreamingAggregate(null,
      parseExprs("name_left", "name"),
      parseExprs("sum(id_left+cost_left)", "total_sum"),
      1.0f);

    final StreamingAggBatch strAggBatch = new StreamingAggBatch(streamAggrConfig, mockInputBatch,
      operatorFixture.getFragmentContext());

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    assertEquals(1, strAggBatch.getRecordCount());

    RowSet actualRowSet = DirectRowSet.fromContainer(strAggBatch.getContainer());
    new RowSetComparison(expectedRowSet1).verify(actualRowSet);

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.EMIT);
    assertEquals(0, strAggBatch.getRecordCount());

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.EMIT);
    assertEquals(2, strAggBatch.getRecordCount());

    actualRowSet = DirectRowSet.fromContainer(strAggBatch.getContainer());
    new RowSetComparison(expectedRowSet2).verify(actualRowSet);

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.NONE);

    // Release memory for row sets
    nonEmptyInputRowSet2.clear();
    expectedRowSet2.clear();
    expectedRowSet1.clear();
  }

  /**
   * Verifies that if StreamingAggr receives multiple non-empty record batch with EMIT outcome in between then it produces
   * output for those input batch correctly. In this case it receives first non-empty batch with OK_NEW_SCHEMA in
   * buildSchema phase followed by an empty batch with EMIT outcome. For this combination it produces output for the
   * record received so far along with EMIT outcome. Then it receives second non-empty batch with OK outcome and
   * produces output for it differently. The test validates that for each output received the order of the records are
   * correct.
   * @throws Exception
   */
  @Test
  public void t6_testStreamingAggrOkFollowedByNone() {
    final RowSet.SingleRowSet nonEmptyInputRowSet2 = operatorFixture.rowSetBuilder(inputSchema)
      .addRow(2, 20, "item2")
      .addRow(3, 30, "item3")
      .addRow(4, 40, "item4")
      .addRow(4, 40, "item4")
      .addRow(5, 50, "item5")
      .addRow(5, 50, "item5")
      .build();

    final RowSet.SingleRowSet expectedRowSet1 = operatorFixture.rowSetBuilder(resultSchema)
      .addRow("item1", (long)11)
      .build();

    final RowSet.SingleRowSet expectedRowSet2 = operatorFixture.rowSetBuilder(resultSchema)
      .addRow("item2", (long)22)
      .addRow("item3", (long)33)
      .addRow("item4", (long)88)
      .addRow("item5", (long)110)
      .build();

    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(nonEmptyInputRowSet.container());
    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(nonEmptyInputRowSet2.container());

    inputOutcomes.add(RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    inputOutcomes.add(RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    inputOutcomes.add(RecordBatch.IterOutcome.EMIT);
    inputOutcomes.add(RecordBatch.IterOutcome.OK);

    final MockRecordBatch mockInputBatch = new MockRecordBatch(operatorFixture.getFragmentContext(), opContext,
      inputContainer, inputOutcomes, emptyInputRowSet.container().getSchema());

    final StreamingAggregate streamAggrConfig = new StreamingAggregate(null,
      parseExprs("name_left", "name"),
      parseExprs("sum(id_left+cost_left)", "total_sum"),
      1.0f);

    final StreamingAggBatch strAggBatch = new StreamingAggBatch(streamAggrConfig, mockInputBatch,
      operatorFixture.getFragmentContext());

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    assertEquals(1, strAggBatch.getRecordCount());

    RowSet actualRowSet = DirectRowSet.fromContainer(strAggBatch.getContainer());
    new RowSetComparison(expectedRowSet1).verify(actualRowSet);

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.EMIT);
    assertEquals(0, strAggBatch.getRecordCount());

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK);
    assertEquals(4, strAggBatch.getRecordCount());

    actualRowSet = DirectRowSet.fromContainer(strAggBatch.getContainer());
    new RowSetComparison(expectedRowSet2).verify(actualRowSet);

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.NONE);

    // Release memory for row sets
    nonEmptyInputRowSet2.clear();
    expectedRowSet2.clear();
    expectedRowSet1.clear();
  }

  /**
   * Normal case
   */
  @Test
  public void t7_testStreamingAggrMultipleEMITOutcome() {
    final RowSet.SingleRowSet nonEmptyInputRowSet2 = operatorFixture.rowSetBuilder(inputSchema)
      .addRow(2, 20, "item2")
      .addRow(3, 30, "item3")
      .build();

    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(nonEmptyInputRowSet.container());
    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(nonEmptyInputRowSet2.container());
    inputContainer.add(emptyInputRowSet.container());

    inputOutcomes.add(RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    inputOutcomes.add(RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    inputOutcomes.add(RecordBatch.IterOutcome.EMIT);
    inputOutcomes.add(RecordBatch.IterOutcome.EMIT);
    inputOutcomes.add(RecordBatch.IterOutcome.EMIT);

    final MockRecordBatch mockInputBatch = new MockRecordBatch(operatorFixture.getFragmentContext(), opContext,
      inputContainer, inputOutcomes, emptyInputRowSet.container().getSchema());

    final StreamingAggregate streamAggrConfig = new StreamingAggregate(null,
      parseExprs("name_left", "name"),
      parseExprs("sum(id_left+cost_left)", "total_sum"),
      1.0f);

    final StreamingAggBatch strAggBatch = new StreamingAggBatch(streamAggrConfig, mockInputBatch,
      operatorFixture.getFragmentContext());

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    assertEquals(1, strAggBatch.getRecordCount());
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.EMIT);
    assertEquals(0, strAggBatch.getRecordCount());
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.EMIT);
    assertEquals(2, strAggBatch.getRecordCount());
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.EMIT);
    assertEquals(0, strAggBatch.getRecordCount());
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.NONE);

    nonEmptyInputRowSet2.clear();
  }

  /**
   *
   */
  @Test
  public void t8_testStreamingAggrMultipleInputToSingleOutputBatch() {

    final RowSet.SingleRowSet nonEmptyInputRowSet2 = operatorFixture.rowSetBuilder(inputSchema)
      .addRow(2, 20, "item2")
      .build();

    final RowSet.SingleRowSet expectedRowSet = operatorFixture.rowSetBuilder(resultSchema)
      .addRow("item1", (long)11)
      .addRow("item2", (long)22)
      .build();

    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(nonEmptyInputRowSet.container());
    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(nonEmptyInputRowSet2.container());
    inputContainer.add(emptyInputRowSet.container());

    inputOutcomes.add(RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    inputOutcomes.add(RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    inputOutcomes.add(RecordBatch.IterOutcome.OK);
    inputOutcomes.add(RecordBatch.IterOutcome.OK);
    inputOutcomes.add(RecordBatch.IterOutcome.EMIT);

    final MockRecordBatch mockInputBatch = new MockRecordBatch(operatorFixture.getFragmentContext(), opContext,
      inputContainer, inputOutcomes, emptyInputRowSet.container().getSchema());

    final StreamingAggregate streamAggrConfig = new StreamingAggregate(null,
      parseExprs("name_left", "name"),
      parseExprs("sum(id_left+cost_left)", "total_sum"),
      1.0f);

    final StreamingAggBatch strAggBatch = new StreamingAggBatch(streamAggrConfig, mockInputBatch,
      operatorFixture.getFragmentContext());

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    assertEquals(2, strAggBatch.getRecordCount());

    RowSet actualRowSet = DirectRowSet.fromContainer(strAggBatch.getContainer());
    new RowSetComparison(expectedRowSet).verify(actualRowSet);

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.EMIT);
    assertEquals(0, strAggBatch.getRecordCount());

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.NONE);

    nonEmptyInputRowSet2.clear();
  }


  /*****************************************************************************************
   Tests for validating regular StreamingAggr behavior with no EMIT outcome
   ******************************************************************************************/
  @Test
  public void t9_testStreamingAgr_WithEmptyNonEmptyBatchesAndOKOutcome() {
    final RowSet.SingleRowSet nonEmptyInputRowSet2 = operatorFixture.rowSetBuilder(inputSchema)
      .addRow(2, 20, "item1")
      .addRow(13, 130, "item13")
      .addRow(13, 130, "item13")
      .addRow(13, 130, "item13")
      .addRow(130, 1300, "item130")
      .addRow(0, 0, "item130")
      .build();

    final RowSet.SingleRowSet nonEmptyInputRowSet3 = operatorFixture.rowSetBuilder(inputSchema)
      .addRow(23, 230, "item23")
      .addRow(3, 33, "item3")
      .addRow(7, 70, "item7")
      .addRow(17, 170, "item7")
      .build();

    final RowSet.SingleRowSet expectedRowSet = operatorFixture.rowSetBuilder(resultSchema)
      .addRow("item1", (long)33)
      .addRow("item13", (long)429)
      .addRow("item130", (long)1430)
      .addRow("item23", (long)253)
      .addRow("item3", (long)36)
      .addRow("item7", (long)264)
      .build();

    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(nonEmptyInputRowSet.container());
    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(nonEmptyInputRowSet2.container());
    inputContainer.add(emptyInputRowSet.container());
    inputContainer.add(nonEmptyInputRowSet3.container());
    inputContainer.add(emptyInputRowSet.container());

    inputOutcomes.add(RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    inputOutcomes.add(RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    inputOutcomes.add(RecordBatch.IterOutcome.OK);
    inputOutcomes.add(RecordBatch.IterOutcome.OK);
    inputOutcomes.add(RecordBatch.IterOutcome.OK);
    inputOutcomes.add(RecordBatch.IterOutcome.OK);
    inputOutcomes.add(RecordBatch.IterOutcome.OK);

    final MockRecordBatch mockInputBatch = new MockRecordBatch(operatorFixture.getFragmentContext(), opContext,
      inputContainer, inputOutcomes, emptyInputRowSet.container().getSchema());

    final StreamingAggregate streamAggrConfig = new StreamingAggregate(null,
      parseExprs("name_left", "name"),
      parseExprs("sum(id_left+cost_left)", "total_sum"),
      1.0f);

    final StreamingAggBatch strAggBatch = new StreamingAggBatch(streamAggrConfig, mockInputBatch,
      operatorFixture.getFragmentContext());

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    assertEquals(6, strAggBatch.getRecordCount());

    RowSet actualRowSet = DirectRowSet.fromContainer(strAggBatch.getContainer());
    new RowSetComparison(expectedRowSet).verify(actualRowSet);

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.NONE);

    nonEmptyInputRowSet2.clear();
    nonEmptyInputRowSet3.clear();
    expectedRowSet.clear();
  }

  @Test
  public void t10_testStreamingAggrWithEmptyDataSet() {
    inputContainer.add(emptyInputRowSet.container());

    inputOutcomes.add(RecordBatch.IterOutcome.OK_NEW_SCHEMA);

    final MockRecordBatch mockInputBatch = new MockRecordBatch(operatorFixture.getFragmentContext(), opContext,
      inputContainer, inputOutcomes, emptyInputRowSet.container().getSchema());

    final StreamingAggregate streamAggrConfig = new StreamingAggregate(null,
        parseExprs("name_left", "name"),
        parseExprs("sum(id_left+cost_left)", "total_sum"),
      1.0f);

    final StreamingAggBatch strAggBatch = new StreamingAggBatch(streamAggrConfig, mockInputBatch,
      operatorFixture.getFragmentContext());

    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.OK_NEW_SCHEMA);
    assertTrue(strAggBatch.next() == RecordBatch.IterOutcome.NONE);
  }
}
