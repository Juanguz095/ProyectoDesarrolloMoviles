import copy
import json
from pathlib import Path
import tempfile
import unittest

from jsonschema import Draft202012Validator, FormatChecker
import yaml
from scripts.check_contract import load_contract

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = load_contract(ROOT / 'contracts/openapi.yaml')
CASES = json.loads((ROOT / 'contracts/examples/schema-cases.json').read_text())


def validator(name):
    return Draft202012Validator({'$ref': '#/components/schemas/' + name, 'components': CONTRACT['components']}, format_checker=FormatChecker())


class SchemaExamples(unittest.TestCase):
    pass


def example_check(case):
    def check(self):
        errors = list(validator(case['schema']).iter_errors(case['value']))
        self.assertEqual(not errors, case['valid'], case['name'])
    return check


for case in CASES:
    setattr(SchemaExamples, 'test_' + case['name'], example_check(case))


class ContractInvariants(unittest.TestCase):
    def test_batch_limit(self):
        event = next(case['value'] for case in CASES if case['name'] == 'event_input')
        self.assertTrue(validator('EventBatch').is_valid({'events': [event] * 100}))
        self.assertFalse(validator('EventBatch').is_valid({'events': [event] * 101}))

    def test_duplicate_yaml_keys_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / 'duplicate.yaml'
            source.write_text('paths: {}\npaths: {}\n')
            with self.assertRaises(yaml.constructor.ConstructorError):
                load_contract(source)

    def test_operations_and_security_are_unambiguous(self):
        operations = []
        for path, item in CONTRACT['paths'].items():
            for method, operation in item.items():
                if method not in ('get', 'post'):
                    continue
                operations.append(operation['operationId'])
                security = operation.get('security', CONTRACT['security'])
                if path in ('/auth/login', '/auth/refresh'):
                    self.assertEqual(security, [])
                elif path == '/devices/enroll':
                    self.assertEqual(security, [{'EnrollmentCode': []}])
                else:
                    self.assertTrue(operation.get('x-roles'))
                    expected = 'DeviceBearer' if operation['x-roles'] == ['device'] else 'UserBearer'
                    self.assertEqual(security, [{expected: []}])
                if '204' in operation['responses']:
                    self.assertNotIn('content', operation['responses']['204'])
                for status, response in operation['responses'].items():
                    if int(status) >= 400:
                        response = CONTRACT['components']['responses'][response['$ref'].rsplit('/', 1)[-1]]
                        content = response['content']['application/problem+json']
                        self.assertEqual(content['example']['status'], int(status))
                        self.assertTrue(validator('Problem').is_valid(content['example']))
        self.assertEqual(len(operations), len(set(operations)))
        self.assertEqual(len(operations), 14)

    def test_composite_detail_and_complete_auth_paths_exist(self):
        self.assertIn('/devices/{deviceId}/events/{eventId}', CONTRACT['paths'])
        self.assertTrue(all('/auth/' + action in CONTRACT['paths'] for action in ('login','refresh','logout')))
        for page in ('EventPage','DevicePage','AuditPage'):
            self.assertIn('nextCursor', CONTRACT['components']['schemas'][page]['required'])


if __name__ == '__main__':
    unittest.main()
