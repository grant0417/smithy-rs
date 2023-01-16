/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.testutil

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.core.smithy.CodegenTarget
import software.amazon.smithy.rust.codegen.core.smithy.ErrorsModule
import software.amazon.smithy.rust.codegen.core.smithy.ModelsModule
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.RustSymbolProvider
import software.amazon.smithy.rust.codegen.core.smithy.generators.StructureGenerator
import software.amazon.smithy.rust.codegen.core.smithy.generators.UnionGenerator
import software.amazon.smithy.rust.codegen.core.smithy.generators.implBlock
import software.amazon.smithy.rust.codegen.core.smithy.generators.renderUnknownVariant
import software.amazon.smithy.rust.codegen.core.smithy.protocols.Protocol
import software.amazon.smithy.rust.codegen.core.smithy.transformers.EventStreamNormalizer
import software.amazon.smithy.rust.codegen.core.smithy.transformers.OperationNormalizer
import software.amazon.smithy.rust.codegen.core.testutil.EventStreamMarshallTestCases.writeMarshallTestCases
import software.amazon.smithy.rust.codegen.core.testutil.EventStreamUnmarshallTestCases.writeUnmarshallTestCases
import software.amazon.smithy.rust.codegen.core.util.hasTrait
import software.amazon.smithy.rust.codegen.core.util.lookup
import software.amazon.smithy.rust.codegen.core.util.outputShape

data class TestEventStreamProject(
    val model: Model,
    val serviceShape: ServiceShape,
    val operationShape: OperationShape,
    val streamShape: UnionShape,
    val symbolProvider: RustSymbolProvider,
    val project: TestWriterDelegator,
)

enum class EventStreamTestVariety {
    Marshall,
    Unmarshall
}

interface EventStreamTestRequirements<C : CodegenContext, B : BuilderGenerator> {
    /** Create a codegen context for the tests */
    fun createCodegenContext(
        model: Model,
        serviceShape: ServiceShape,
        protocolShapeId: ShapeId,
        codegenTarget: CodegenTarget,
    ): C

    fun createBuilderGenerator(codegenContext: C, structureShape: StructureShape): B

    /** Render the event stream marshall/unmarshall code generator */
    fun renderGenerator(
        codegenContext: C,
        project: TestEventStreamProject,
        protocol: Protocol,
    ): RuntimeType

    /** Render an operation error for the given operation and error shapes */
    fun renderOperationError(
        writer: RustWriter,
        model: Model,
        symbolProvider: RustSymbolProvider,
        operationSymbol: Symbol,
        errors: List<StructureShape>,
    )
}

object EventStreamTestTools {
    fun <C : CodegenContext, B : BuilderGenerator> runTestCase(
        testCase: EventStreamTestModels.TestCase,
        requirements: EventStreamTestRequirements<C, B>,
        codegenTarget: CodegenTarget,
        variety: EventStreamTestVariety,
    ) {
        val model = EventStreamNormalizer.transform(OperationNormalizer.transform(testCase.model))
        val serviceShape = model.expectShape(ShapeId.from("test#TestService")) as ServiceShape
        val codegenContext = requirements.createCodegenContext(
            model,
            serviceShape,
            ShapeId.from(testCase.protocolShapeId),
            codegenTarget,
        )
        val test = generateTestProject(requirements, codegenContext, codegenTarget)
        val protocol = testCase.protocolBuilder(codegenContext)
        val generator = requirements.renderGenerator(codegenContext, test, protocol)

        test.project.lib {
            when (variety) {
                EventStreamTestVariety.Marshall -> writeMarshallTestCases(testCase, generator)
                EventStreamTestVariety.Unmarshall -> writeUnmarshallTestCases(testCase, codegenTarget, generator)
            }
        }
        test.project.compileAndTest()
    }

    private fun <C : CodegenContext, B : BuilderGenerator> renderBuilderForShape(
        requirements: EventStreamTestRequirements<C, B>,
        writer: RustWriter,
        codegenContext: C, structureShape: StructureShape,
    ) {
        val builderGenerator = requirements.createBuilderGenerator(codegenContext, structureShape)

        builderGenerator.apply {
            render(writer)
            writer.implBlock(structureShape, codegenContext.symbolProvider) {
                renderConvenienceMethod(writer)
            }
        }
    }

    private fun <C : CodegenContext, B : BuilderGenerator> generateTestProject(
        requirements: EventStreamTestRequirements<C, B>,
        codegenContext: C,
        codegenTarget: CodegenTarget,
    ): TestEventStreamProject {
        val model = codegenContext.model
        val symbolProvider = codegenContext.symbolProvider
        val operationShape = model.expectShape(ShapeId.from("test#TestStreamOp")) as OperationShape
        val unionShape = model.expectShape(ShapeId.from("test#TestStream")) as UnionShape

        val project = TestWorkspace.testProject(symbolProvider)
        val operationSymbol = symbolProvider.toSymbol(operationShape)
        project.withModule(ErrorsModule) {
            val errors = model.structureShapes.filter { shape -> shape.hasTrait<ErrorTrait>() }
            requirements.renderOperationError(this, model, symbolProvider, operationSymbol, errors)
            requirements.renderOperationError(this, model, symbolProvider, symbolProvider.toSymbol(unionShape), errors)
            for (shape in errors) {
                StructureGenerator(model, symbolProvider, this, shape).render(codegenTarget)
                renderBuilderForShape(requirements, this, codegenContext, shape)
            }
        }
        project.withModule(ModelsModule) {
            val inputOutput = model.lookup<StructureShape>("test#TestStreamInputOutput")
            recursivelyGenerateModels(requirements, codegenContext, inputOutput, this, codegenTarget)
        }
        project.withModule(RustModule.Output) {
            val outputShape = operationShape.outputShape(model)
            val builderGenerator = requirements.createBuilderGenerator(codegenContext, outputShape)
            outputShape.renderWithModelBuilder(model, symbolProvider, this, builderGenerator)
        }
        return TestEventStreamProject(
            model,
            codegenContext.serviceShape,
            operationShape,
            unionShape,
            symbolProvider,
            project,
        )
    }

    private fun <C : CodegenContext, B : BuilderGenerator> recursivelyGenerateModels(
        requirements: EventStreamTestRequirements<C, B>,
        codegenContext: C,
        shape: Shape,
        writer: RustWriter,
        mode: CodegenTarget,
    ) {
        val model = codegenContext.model
        val symbolProvider = codegenContext.symbolProvider
        for (member in shape.members()) {
            if (member.target.namespace == "smithy.api") {
                continue
            }
            val target = model.expectShape(member.target)
            when (target) {
                is StructureShape -> {
                    val builderGenerator = requirements.createBuilderGenerator(codegenContext, target)
                    target.renderWithModelBuilder(model, symbolProvider, writer, builderGenerator)
                }
                is UnionShape -> UnionGenerator(
                    model,
                    symbolProvider,
                    writer,
                    target,
                    renderUnknownVariant = mode.renderUnknownVariant(),
                ).render()
                else -> TODO("EventStreamTestTools doesn't support rendering $target")
            }
            recursivelyGenerateModels(requirements, codegenContext, target, writer, mode)
        }
    }
}
