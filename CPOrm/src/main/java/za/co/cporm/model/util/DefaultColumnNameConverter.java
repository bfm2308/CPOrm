package za.co.cporm.model.util;

import za.co.cporm.model.naming.ColumnNameConverter;

/**
 * Contains common naming utilities to convert strings to SQL formats
 */
public class DefaultColumnNameConverter implements ColumnNameConverter {

    /**
     * Converts the source string to a SQL compatible string.  It does this
     * by checking for camelcase values, and applying a underscore '_' to whenever one is found.  All
     * characters are also converted to lower case.
     *
     * Example: If the provided string is "helloWorld" the resulting string will be "hello_world"
     * @param original String to converted
     * @return the converted string
     */
    public static String getSQLName(String original){

        StringBuilder sqlName = new StringBuilder();

        for (char character : original.toCharArray()) {

            if(Character.isUpperCase(character) && sqlName.length() > 0) sqlName.append("_");

            sqlName.append(Character.toLowerCase(character));
        }

        return sqlName.toString();
    }

    /**
     * Converts the source string to a SQL compatible string.  It does this
     * by checking for camelcase values, and applying a underscore '_' to whenever one is found.  All
     * characters are also converted to lower case.
     *
     * Example: If the provided string is "helloWorld" the resulting string will be "hello_world"
     * @param fieldName String to convert
     * @return the converted string
     */
    @Override
    public String convertToSql(String fieldName) {
        StringBuilder sqlName = new StringBuilder();

        for (char character : fieldName.toCharArray()) {

            if(Character.isUpperCase(character) && sqlName.length() > 0) sqlName.append("_");

            sqlName.append(Character.toLowerCase(character));
        }

        return sqlName.toString();
    }
}
