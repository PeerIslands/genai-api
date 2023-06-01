package io.peerislands.service

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.mongodb.client.MongoClients
import io.peerislands.data.companiesSchema
import io.peerislands.data.gradesSchema
import io.peerislands.data.inspectionSchema
import org.bson.Document
import java.util.Scanner


const val OPEN_PAREN = "("
const val CLOSE_PAREN = ")"
val OBJECT_MAPPER: ObjectMapper = ObjectMapper().registerModule(
    KotlinModule.Builder()
        .withReflectionCacheSize(512)
        .configure(KotlinFeature.NullToEmptyCollection, false)
        .configure(KotlinFeature.NullToEmptyMap, false)
        .configure(KotlinFeature.NullIsSameAsDefault, false)
        .configure(KotlinFeature.StrictNullChecks, false)
        .build())
    .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
    .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)

fun validateResponse(answer: String): Boolean {
    //          - Check for syntax errors
    val validSyntax = validateSyntax(answer)
    //          - Check for semantic errors - field names, data types, etc.
    val validSemantics = validateSemantics(answer)

    return validSyntax && validSemantics
}

fun validateSemantics(answer: String): Boolean {
    val extractedQuery = extractQuery(answer)
    return when (getOperation(answer)) {
        "insertOne", "insertMany" -> true // No validation for now
        "find" -> validateFindQueryFields(extractedQuery[0] as Map<String, Any>)
        else -> true // Need to be false. Temporarily returning true to bypass other operations
    }
}

fun validateSyntax(answer: String): Boolean {
    val client = MongoClients.create()
    val db = client.getDatabase("test")
    val collection = db.getCollection("test")
    return try {
        val extractedQuery = extractQuery(answer)
        when (getOperation(answer)) {
            "insertOne" -> collection.insertOne(Document(extractedQuery[0] as Map<String, Any>))
            "insertMany" -> collection.insertMany((extractedQuery[0] as List<Map<String, Any>>).map { item ->
                Document(
                    item
                )
            })
            "aggregate" -> collection.aggregate((extractedQuery[0] as List<Map<String, Any>>).map { item ->
                Document(
                    item
                )
            }).first()
            "find" -> collection.find(Document(extractedQuery[0] as Map<String, Any>)).first()
        }
        true
    } catch (e: Exception) {
        false
    }
}

fun extractQuery(answer: String): List<Any> {
    val funArgs = answer.substring(answer.indexOf(OPEN_PAREN) + 1, answer.lastIndexOf(CLOSE_PAREN))
    return OBJECT_MAPPER.readValue("[$funArgs]", object : TypeReference<List<Any>>() {})
}

fun getOperation(answer: String): String {
    return when {
        answer.contains("insertOne") -> "insertOne"
        answer.contains("insertMany") -> "insertMany"
        answer.contains("aggregate") -> "aggregate"
        // Temporarily handling all other operations as find. Need to add exhaustive list in future
        else -> "find"
    }
}

fun getFieldsFromSchema(vararg schemas: String): List<String> {
    val fieldList = mutableListOf<String>()
    schemas.forEach {
            schema -> fieldList.addAll(getFieldsFromSchema(schema))
    }
    return fieldList
}
fun getFieldsFromSchema(schema: String): List<String> {
    val fieldList = mutableListOf<String>()
    Scanner(schema).use {
            sc ->
        val parentList = mutableListOf<String>()
        var intendCount = 0
        while(sc.hasNextLine()){
            val content = sc.nextLine().split(":")

            while(intendCount != content[0].countPrefixIntend()) {
                parentList.removeLast()
                intendCount--
            }
            if(parentList.isNotEmpty()){
                fieldList.add(parentList.reduce { acc, s -> "$acc.$s" }+"."+content[0].trim())
            }else{
                fieldList.add(content[0].trim())
            }

            if((content[1].trim() == "Object") or (content[1].trim() == "Array")){
                intendCount++
                parentList.add(content[0].trim())
            }

        }
    }
    return fieldList
}

fun String.countPrefixIntend(intendSpaceCount: Int = 4): Int {
    var spaceCount = 0
    for(char in this) {
        if(char == ' '){
            spaceCount++
        }else {
            break
        }
    }
    return spaceCount/intendSpaceCount
}

fun extractFieldsFromQuery(mQuery: Map<String, Any>): List<String> {
    val resultList = mutableSetOf<String>()
    mQuery.entries.forEach {
            entry ->
        if(entry.value is Map<*, *>) {
            resultList.addAll(extractFieldsFromQuery(entry.value as Map<String, Any>))
        } else if(entry.value is List<*>) {
            (entry.value as List<Any>).forEach {
                    item ->
                if(item is Map<*, *>) {
                    resultList.addAll(extractFieldsFromQuery(entry.value as Map<String, Any>))
                }
            }
        }
        if(!entry.key.startsWith("\$")){
            resultList.add(entry.key.toMongoDBFieldName())
        }
    }
    return resultList.toList()
}

fun String.toMongoDBFieldName(): String {
    // Regex to remove array filter $[*]
    val arrayFilterRegex = Regex(pattern = "\\$\\[.*\\]", options = setOf(RegexOption.IGNORE_CASE))
    // Regex to remove array access by index
    val arrayIndexRegex = Regex(pattern = "\\.[0-9]+", options = setOf(RegexOption.IGNORE_CASE))
    return this.replace(arrayFilterRegex, "").replace(arrayIndexRegex, "")
        .replace("\$","").replace("..",".")
}

fun validateFindQueryFields(query: Map<String, Any>): Boolean {
    val fieldList = getFieldsFromSchema(inspectionSchema, gradesSchema, companiesSchema)
    return extractFieldsFromQuery(query).all { fieldList.contains(it)  }
}