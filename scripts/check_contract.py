"""Validate the OpenAPI document without contacting its illustrative server."""
from pathlib import Path
import yaml
from openapi_spec_validator import validate


class UniqueKeyLoader(yaml.SafeLoader):
    def construct_mapping(self, node, deep=False):
        keys = [self.construct_object(key, deep=deep) for key, _ in node.value]
        if len(keys) != len(set(keys)):
            raise yaml.constructor.ConstructorError('mapping', node.start_mark, 'Duplicate YAML key', node.start_mark)
        return super().construct_mapping(node, deep=deep)


def load_contract(source):
    return yaml.load(Path(source).read_text(encoding='utf-8'), Loader=UniqueKeyLoader)


if __name__ == '__main__':
    root = Path(__file__).resolve().parents[1] / 'contracts'
    for name in ('openapi.yaml', 'prototype-v0.1.yaml'):
        contract = load_contract(root / name)
        validate(contract)
        print(f'{name} valid: {contract["info"]["version"]}, {len(contract["paths"])} paths')
